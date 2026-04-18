package velox.api.layer1.simplified.demo;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeListener;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.settings.StrategySettingsVersion;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.simplified.HistoricalDataListener;
import velox.api.layer1.simplified.Indicator;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.IntervalListener;
import velox.api.layer1.simplified.Intervals;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;
import velox.gui.StrategyPanel;
import velox.gui.colors.ColorsConfigItem;
import velox.gui.utils.GuiUtils;

/**
 * Absorption Indicator for Bookmap.
 *
 * Detects price levels where large passive limit orders absorb aggressive
 * market orders. When traded volume at a specific price level exceeds a
 * configurable threshold within a time window, an absorption mark is placed.
 *
 * - Passive buyers: buy limit orders absorbing sell market orders (bid aggressors)
 * - Passive sellers: sell limit orders absorbing buy market orders (ask aggressors)
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Absorption")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class AbsorptionIndicator implements
        CustomModule,
        TradeDataListener,
        HistoricalDataListener,
        TimeListener,
        IntervalListener,
        CustomSettingsPanelProvider {

    // ════════════════════════════════════════════════════════════
    //  SETTINGS
    // ════════════════════════════════════════════════════════════

    @StrategySettingsVersion(currentVersion = 1, compatibleVersions = {})
    public static class Settings {
        // --- Detection ---
        public boolean autoMode = true;
        public int timePeriodSec = 5;
        public double manualSize = 500;
        public int sdIntervalSec = 300;
        public double sdMultiplier = 2.0;

        // --- Visual ---
        public boolean showIcons = true;
        public boolean showVolume = true;
        public int iconsSize = 17;
        public int iconsOffset = 100;
        public int offsetLineWidth = 1;

        // --- Passive buyers colors (stored as RGB int for serialization) ---
        public int buyerIconColorRgb = 0x00E676;
        public int buyerTextColorRgb = 0x00E676;

        // --- Passive sellers colors ---
        public int sellerIconColorRgb = 0xE040FB;
        public int sellerTextColorRgb = 0xE040FB;

        // --- Background ---
        public int bgColorRgb = 0x2A2E39;
        public int bgColorAlpha = 204;

        // --- Aggregation ---
        public boolean hAgg = true;
        public int hAggVal = 0;
        public boolean vAgg = true;
        public int vAggVal = 0;

        // --- Trade dots ---
        public boolean showDots = true;
        public int dotsSize = 15;
        public String dotsShape = "RECTANGLE";

        // --- Color helpers ---
        public Color buyerIconColor()  { return new Color(buyerIconColorRgb); }
        public Color buyerTextColor()  { return new Color(buyerTextColorRgb); }
        public Color sellerIconColor() { return new Color(sellerIconColorRgb); }
        public Color sellerTextColor() { return new Color(sellerTextColorRgb); }

        public Color bgColor() {
            return new Color(
                (bgColorRgb >> 16) & 0xFF,
                (bgColorRgb >> 8) & 0xFF,
                bgColorRgb & 0xFF,
                bgColorAlpha);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  STATE
    // ════════════════════════════════════════════════════════════

    private Api api;
    private Indicator indicator;
    private Settings settings;
    private double pips;
    private long currentTime;

    /**
     * Volume accumulation per price level.
     * Key: price level (int = price / pips).
     * Value: long[3] = {bidAggressorVolume, askAggressorVolume, windowStartTimeNs}.
     *
     * bidAggressorVolume tracks sell market orders hitting buy limits → passive buyer detection.
     * askAggressorVolume tracks buy market orders hitting sell limits → passive seller detection.
     */
    private final Map<Integer, long[]> levelVolumes = new HashMap<>();

    /** Rolling history of per-level volumes for SD computation. */
    private final LinkedList<Double> volumeHistory = new LinkedList<>();
    private static final int MAX_VOLUME_HISTORY = 5000;

    // Aggregation tracking
    private long lastBuyTime = Long.MIN_VALUE;
    private int lastBuyLevel = Integer.MIN_VALUE;
    private long lastBuyAggVol = 0;

    private long lastSellTime = Long.MIN_VALUE;
    private int lastSellLevel = Integer.MIN_VALUE;
    private long lastSellAggVol = 0;

    // Rendering cache
    private Font labelFont;
    private FontMetrics labelFM;

    // ════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════════════

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.api = api;
        this.pips = info.pips;
        this.settings = api.getSettings(Settings.class);

        indicator = api.registerIndicator("Absorption", GraphType.PRIMARY);
        indicator.setColor(Color.WHITE);

        rebuildFont();
    }

    @Override
    public void stop() {
        levelVolumes.clear();
        volumeHistory.clear();
    }

    @Override
    public void onTimestamp(long t) {
        currentTime = t;
    }

    @Override
    public long getInterval() {
        return Intervals.INTERVAL_100_MILLISECONDS;
    }

    @Override
    public void onInterval() {
        cleanupExpiredWindows();
    }

    // ════════════════════════════════════════════════════════════
    //  TRADE PROCESSING
    // ════════════════════════════════════════════════════════════

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        int level = (int) Math.round(price / pips);
        long windowNs = settings.timePeriodSec * Intervals.INTERVAL_1_SECOND;

        long[] vol = levelVolumes.get(level);
        if (vol == null) {
            vol = new long[]{0, 0, currentTime};
            levelVolumes.put(level, vol);
        }

        // Reset if window expired
        if (currentTime - vol[2] > windowNs) {
            recordForSD(vol);
            vol[0] = 0;
            vol[1] = 0;
            vol[2] = currentTime;
        }

        // Accumulate by aggressor side
        if (tradeInfo.isBidAggressor) {
            vol[0] += size; // sell aggressor → passive buyer tracking
        } else {
            vol[1] += size; // buy aggressor → passive seller tracking
        }

        double threshold = computeThreshold();

        // Check passive buyer absorption
        if (vol[0] >= threshold) {
            long absVol = vol[0];
            vol[0] = 0;
            addVolumeToHistory(absVol);
            drawAbsorption(price, level, absVol, true);
        }

        // Check passive seller absorption
        if (vol[1] >= threshold) {
            long absVol = vol[1];
            vol[1] = 0;
            addVolumeToHistory(absVol);
            drawAbsorption(price, level, absVol, false);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  THRESHOLD / SD
    // ════════════════════════════════════════════════════════════

    private double computeThreshold() {
        if (!settings.autoMode) {
            return settings.manualSize;
        }
        int n = volumeHistory.size();
        if (n < 10) {
            return settings.manualSize;
        }

        double sum = 0;
        double sumSq = 0;
        for (double v : volumeHistory) {
            sum += v;
            sumSq += v * v;
        }
        double mean = sum / n;
        double variance = (sumSq / n) - (mean * mean);
        double sd = Math.sqrt(Math.max(0, variance));
        return mean + settings.sdMultiplier * sd;
    }

    private void addVolumeToHistory(double vol) {
        volumeHistory.addLast(vol);
        while (volumeHistory.size() > MAX_VOLUME_HISTORY) {
            volumeHistory.removeFirst();
        }
    }

    private void recordForSD(long[] vol) {
        double maxVol = Math.max(vol[0], vol[1]);
        if (maxVol > 0) {
            addVolumeToHistory(maxVol);
        }
    }

    private void cleanupExpiredWindows() {
        long windowNs = settings.timePeriodSec * Intervals.INTERVAL_1_SECOND;
        long cutoff = currentTime - windowNs * 2;

        Iterator<Map.Entry<Integer, long[]>> it = levelVolumes.entrySet().iterator();
        while (it.hasNext()) {
            long[] vol = it.next().getValue();
            if (vol[2] < cutoff) {
                recordForSD(vol);
                it.remove();
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  DRAWING
    // ════════════════════════════════════════════════════════════

    private void drawAbsorption(double price, int level, long volume, boolean isPassiveBuyer) {
        // --- Aggregation check ---
        boolean merged = false;
        if (settings.hAgg && settings.hAggVal > 0) {
            long timeThresholdNs = settings.hAggVal * Intervals.INTERVAL_1_SECOND;

            if (isPassiveBuyer && (currentTime - lastBuyTime) < timeThresholdNs) {
                if (!settings.vAgg || settings.vAggVal == 0
                        || Math.abs(level - lastBuyLevel) <= settings.vAggVal) {
                    merged = true;
                    lastBuyAggVol += volume;
                    volume = lastBuyAggVol;
                }
            } else if (!isPassiveBuyer && (currentTime - lastSellTime) < timeThresholdNs) {
                if (!settings.vAgg || settings.vAggVal == 0
                        || Math.abs(level - lastSellLevel) <= settings.vAggVal) {
                    merged = true;
                    lastSellAggVol += volume;
                    volume = lastSellAggVol;
                }
            }
        }

        if (!merged) {
            if (isPassiveBuyer) {
                lastBuyAggVol = volume;
            } else {
                lastSellAggVol = volume;
            }
        }

        if (isPassiveBuyer) {
            lastBuyTime = currentTime;
            lastBuyLevel = level;
        } else {
            lastSellTime = currentTime;
            lastSellLevel = level;
        }

        // --- Render ---
        // Image layout for both sides: dot at TOP (at price level), dotted line, label at bottom.
        // addIcon places the image's top-left at (price + iconY) in screen coords (Y increases downward).
        // We shift up by dotR so the dot center lands exactly at the absorption price.
        BufferedImage icon = renderMark(volume, isPassiveBuyer);
        int iconX = -icon.getWidth() / 2;
        int iconY = -(settings.dotsSize / 2); // center dot on price

        indicator.addIcon(price, icon, iconX, iconY);
    }

    private BufferedImage renderMark(long volume, boolean isPassiveBuyer) {
        Color iconColor = isPassiveBuyer ? settings.buyerIconColor() : settings.sellerIconColor();
        Color textColor = isPassiveBuyer ? settings.buyerTextColor() : settings.sellerTextColor();
        Color bgColor = settings.bgColor();

        String volText = settings.showVolume ? formatVolume(volume) : "";

        // Dimensions
        int textW = volText.isEmpty() ? 0 : labelFM.stringWidth(volText);
        int padH = 6, padV = 3;
        int labelW = settings.showIcons ? Math.max(settings.iconsSize, textW + padH * 2) : 0;
        int labelH = settings.showIcons ? labelFM.getHeight() + padV * 2 : 0;
        int dotSz = settings.showDots ? settings.dotsSize : 0;
        int offset = (settings.showIcons && settings.showDots) ? settings.iconsOffset : 0;

        int imgW = Math.max(Math.max(labelW, dotSz), 4) + 4;
        int imgH = Math.max(dotSz + offset + labelH, 1) + 2;

        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(labelFont);

        int cx = imgW / 2;

        // Same layout for passive buyers AND sellers:
        // dot at top (maps to absorption price), dotted line down, label at bottom.
        // Color alone distinguishes buyer (green) from seller (magenta).
        {
            int y = 0;
            // 1) Dot at top — this is the trade dot at the exact absorption price
            if (settings.showDots) {
                g.setColor(iconColor);
                drawDot(g, cx - dotSz / 2, y, dotSz);
                y += dotSz;
            }
            // 2) Dotted offset line
            if (settings.showDots && settings.showIcons && offset > 0) {
                drawDottedLine(g, cx, y, cx, y + offset, iconColor);
                y += offset;
            }
            // 3) Volume label
            if (settings.showIcons) {
                drawLabel(g, cx, y, labelW, labelH, volText, textW, padV, bgColor, iconColor, textColor);
            }
        }
        if (false) {
            // Dead code block — kept to preserve structure for future seller-above layout
            int y = 0;
            if (settings.showIcons) {
                drawLabel(g, cx, y, labelW, labelH, volText, textW, padV, bgColor, iconColor, textColor);
                y += labelH;
            }
            if (settings.showDots && settings.showIcons && offset > 0) {
                drawDottedLine(g, cx, y, cx, y + offset, iconColor);
                y += offset;
            }
            // 3) Dot at bottom
            if (settings.showDots) {
                g.setColor(iconColor);
                drawDot(g, cx - dotSz / 2, y, dotSz);
            }
        }

        g.dispose();
        return img;
    }

    private void drawDot(Graphics2D g, int x, int y, int size) {
        if ("CIRCLE".equals(settings.dotsShape)) {
            g.fillOval(x, y, size, size);
        } else {
            g.fillRect(x, y, size, size);
        }
    }

    private void drawDottedLine(Graphics2D g, int x1, int y1, int x2, int y2, Color color) {
        Stroke prev = g.getStroke();
        g.setColor(color);
        g.setStroke(new BasicStroke(
                settings.offsetLineWidth,
                BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER,
                10.0f,
                new float[]{3.0f, 3.0f},
                0.0f));
        g.drawLine(x1, y1, x2, y2);
        g.setStroke(prev);
    }

    private void drawLabel(Graphics2D g, int cx, int y, int w, int h,
                           String text, int textW, int padV,
                           Color bg, Color border, Color textColor) {
        int lx = cx - w / 2;
        g.setColor(bg);
        g.fillRoundRect(lx, y, w, h, 6, 6);
        g.setColor(border);
        g.drawRoundRect(lx, y, w, h, 6, 6);
        if (!text.isEmpty()) {
            g.setColor(textColor);
            g.drawString(text, cx - textW / 2, y + padV + labelFM.getAscent());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════

    private void rebuildFont() {
        int fontSize = Math.max(8, settings.iconsSize - 4);
        labelFont = new Font("SansSerif", Font.BOLD, fontSize);
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tmp.createGraphics();
        g.setFont(labelFont);
        labelFM = g.getFontMetrics();
        g.dispose();
    }

    private static String formatVolume(long v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 10_000)    return String.format("%.1fK", v / 1_000.0);
        return String.valueOf(v);
    }

    // ════════════════════════════════════════════════════════════
    //  SETTINGS PANEL
    // ════════════════════════════════════════════════════════════

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        return buildPanels(settings, api);
    }

    /**
     * Optional: generate disabled UI for when the module is not loaded.
     */
    public static StrategyPanel[] getCustomDisabledSettingsPanels() {
        return buildPanels(new Settings(), null);
    }

    private static StrategyPanel[] buildPanels(Settings s, Api api) {
        StrategyPanel detPanel = buildDetectionPanel(s, api);
        StrategyPanel visPanel = buildVisualPanel(s, api);
        StrategyPanel[] panels = new StrategyPanel[]{detPanel, visPanel};
        if (api == null) {
            for (StrategyPanel p : panels) {
                GuiUtils.setPanelEnabled(p, false);
            }
        }
        return panels;
    }

    // --- Detection settings panel ---

    private static StrategyPanel buildDetectionPanel(Settings s, Api api) {
        StrategyPanel panel = new StrategyPanel("Detection settings");
        panel.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        int row = 0;

        // Auto mode checkbox
        JCheckBox autoCheck = new JCheckBox("Auto (SD threshold)", s.autoMode);
        c.gridx = 0; c.gridy = row; c.gridwidth = 2;
        panel.add(autoCheck, c);
        row++;

        // Time period
        c.gridwidth = 1;
        c.gridx = 0; c.gridy = row; c.weightx = 0.3;
        panel.add(new JLabel("Time period (sec)"), c);
        JSpinner timeSpin = new JSpinner(new SpinnerNumberModel(s.timePeriodSec, 1, 300, 1));
        c.gridx = 1; c.weightx = 0.7;
        panel.add(timeSpin, c);
        row++;

        // Manual size
        c.gridx = 0; c.gridy = row; c.weightx = 0.3;
        panel.add(new JLabel("Size (manual)"), c);
        JSpinner sizeSpin = new JSpinner(new SpinnerNumberModel(s.manualSize, 1, 1_000_000, 10));
        c.gridx = 1; c.weightx = 0.7;
        panel.add(sizeSpin, c);
        row++;

        // SD interval
        c.gridx = 0; c.gridy = row; c.weightx = 0.3;
        panel.add(new JLabel("SD interval (sec)"), c);
        JSpinner sdIntSpin = new JSpinner(new SpinnerNumberModel(s.sdIntervalSec, 10, 3600, 10));
        c.gridx = 1; c.weightx = 0.7;
        panel.add(sdIntSpin, c);
        row++;

        // SD multiplier
        c.gridx = 0; c.gridy = row; c.weightx = 0.3;
        panel.add(new JLabel("SD multiplier"), c);
        JSpinner sdMultSpin = new JSpinner(new SpinnerNumberModel(s.sdMultiplier, 0.1, 10.0, 0.1));
        c.gridx = 1; c.weightx = 0.7;
        panel.add(sdMultSpin, c);

        // --- Listeners ---
        if (api != null) {
            autoCheck.addActionListener(e -> { s.autoMode = autoCheck.isSelected(); api.setSettings(s); });
            timeSpin.addChangeListener(e -> { s.timePeriodSec = (Integer) timeSpin.getValue(); api.setSettings(s); });
            sizeSpin.addChangeListener(e -> { s.manualSize = ((Number) sizeSpin.getValue()).doubleValue(); api.setSettings(s); });
            sdIntSpin.addChangeListener(e -> { s.sdIntervalSec = (Integer) sdIntSpin.getValue(); api.setSettings(s); });
            sdMultSpin.addChangeListener(e -> { s.sdMultiplier = ((Number) sdMultSpin.getValue()).doubleValue(); api.setSettings(s); });

            // Toggle manual size enabled state based on auto mode
            sizeSpin.setEnabled(!s.autoMode);
            autoCheck.addActionListener(e -> sizeSpin.setEnabled(!autoCheck.isSelected()));
        }

        return panel;
    }

    // --- Visual settings panel (matches Bookmap screenshot 1:1) ---

    private static StrategyPanel buildVisualPanel(Settings s, Api api) {
        StrategyPanel panel = new StrategyPanel("Visual settings (all instruments)");
        panel.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 6, 3, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        // ── Show icons / Show volume ──
        JCheckBox showIconsCheck = new JCheckBox("Show icons", s.showIcons);
        JCheckBox showVolumeCheck = new JCheckBox("Show volume", s.showVolume);
        c.gridx = 0; c.gridy = row; c.weightx = 0.5;
        panel.add(showIconsCheck, c);
        c.gridx = 1;
        panel.add(showVolumeCheck, c);
        row++;

        // ── Icons size slider ──
        row = addSlider(panel, c, row, "Icons size", s.iconsSize, 1, 50,
                val -> { s.iconsSize = val; if (api != null) api.setSettings(s); });

        // ── Icons offset slider ──
        row = addSlider(panel, c, row, "Icons offset", s.iconsOffset, 0, 500,
                val -> { s.iconsOffset = val; if (api != null) api.setSettings(s); });

        // ── Offset line width slider ──
        row = addSlider(panel, c, row, "Offset line width", s.offsetLineWidth, 1, 5,
                val -> { s.offsetLineWidth = val; if (api != null) api.setSettings(s); });

        // ── Color headers ──
        c.gridx = 0; c.gridy = row; c.weightx = 0.3;
        panel.add(new JLabel(""), c);
        c.gridx = 1; c.weightx = 0.35;
        panel.add(centeredLabel("Passive buyers"), c);
        // We reuse column 1 split via a sub-panel for buyer/seller side by side
        // Actually, let's use a 3-column layout for color rows
        row++;

        // ── Icon color ──
        row = addColorRow(panel, c, row, "Icon color",
                s.buyerIconColor(), new Color(0x00E676),
                color -> { s.buyerIconColorRgb = color.getRGB() & 0xFFFFFF; if (api != null) api.setSettings(s); },
                s.sellerIconColor(), new Color(0xE040FB),
                color -> { s.sellerIconColorRgb = color.getRGB() & 0xFFFFFF; if (api != null) api.setSettings(s); });

        // ── Text color ──
        row = addColorRow(panel, c, row, "Text color",
                s.buyerTextColor(), new Color(0x00E676),
                color -> { s.buyerTextColorRgb = color.getRGB() & 0xFFFFFF; if (api != null) api.setSettings(s); },
                s.sellerTextColor(), new Color(0xE040FB),
                color -> { s.sellerTextColorRgb = color.getRGB() & 0xFFFFFF; if (api != null) api.setSettings(s); });

        // ── Background color ──
        {
            c.gridx = 0; c.gridy = row; c.weightx = 0.3; c.gridwidth = 1;
            panel.add(new JLabel("Background"), c);

            ColorsConfigItem bgColorItem = new ColorsConfigItem(
                    s.bgColor(), new Color(0x2A, 0x2E, 0x39, 0xCC), "Background",
                    color -> {
                        s.bgColorRgb = color.getRGB() & 0xFFFFFF;
                        s.bgColorAlpha = color.getAlpha();
                        if (api != null) api.setSettings(s);
                    });
            c.gridx = 1; c.weightx = 0.7; c.gridwidth = 2;
            panel.add(bgColorItem, c);
            c.gridwidth = 1;
            row++;
        }

        // ── Horizontal aggregation ──
        row = addAggRow(panel, c, row, "Horizontal\naggregation", s.hAgg, s.hAggVal, 0, 50,
                (enabled, val) -> {
                    s.hAgg = enabled;
                    s.hAggVal = val;
                    if (api != null) api.setSettings(s);
                });

        // ── Vertical aggregation ──
        row = addAggRow(panel, c, row, "Vertical\naggregation", s.vAgg, s.vAggVal, 0, 50,
                (enabled, val) -> {
                    s.vAgg = enabled;
                    s.vAggVal = val;
                    if (api != null) api.setSettings(s);
                });

        // ── Show trade dots ──
        JCheckBox showDotsCheck = new JCheckBox("Show trade dots", s.showDots);
        c.gridx = 0; c.gridy = row; c.weightx = 1; c.gridwidth = 3;
        panel.add(showDotsCheck, c);
        c.gridwidth = 1;
        row++;

        // ── Dots size slider ──
        row = addSlider(panel, c, row, "Dots size", s.dotsSize, 1, 50,
                val -> { s.dotsSize = val; if (api != null) api.setSettings(s); });

        // ── Dots shape dropdown ──
        c.gridx = 0; c.gridy = row; c.weightx = 0.3;
        panel.add(new JLabel("Dots shape"), c);
        JComboBox<String> shapeCombo = new JComboBox<>(new String[]{"RECTANGLE", "CIRCLE"});
        shapeCombo.setSelectedItem(s.dotsShape);
        c.gridx = 1; c.weightx = 0.7; c.gridwidth = 2;
        panel.add(shapeCombo, c);
        c.gridwidth = 1;

        // --- Listeners ---
        if (api != null) {
            showIconsCheck.addActionListener(e -> { s.showIcons = showIconsCheck.isSelected(); api.setSettings(s); });
            showVolumeCheck.addActionListener(e -> { s.showVolume = showVolumeCheck.isSelected(); api.setSettings(s); });
            showDotsCheck.addActionListener(e -> { s.showDots = showDotsCheck.isSelected(); api.setSettings(s); });
            shapeCombo.addActionListener(e -> { s.dotsShape = (String) shapeCombo.getSelectedItem(); api.setSettings(s); });
        }

        return panel;
    }

    // ════════════════════════════════════════════════════════════
    //  UI HELPER METHODS
    // ════════════════════════════════════════════════════════════

    /** Adds a labeled slider row. Returns next row index. */
    private static int addSlider(JPanel panel, GridBagConstraints c, int row,
                                 String label, int value, int min, int max,
                                 java.util.function.IntConsumer onChange) {
        c.gridx = 0; c.gridy = row; c.weightx = 0.3;
        panel.add(new JLabel(label), c);

        JPanel sliderPanel = new JPanel(new BorderLayout(4, 0));
        sliderPanel.setOpaque(false);
        JSlider slider = new JSlider(min, max, value);
        JLabel valLabel = new JLabel(String.valueOf(value), SwingConstants.RIGHT);
        valLabel.setPreferredSize(new Dimension(35, valLabel.getPreferredSize().height));
        slider.addChangeListener(e -> {
            valLabel.setText(String.valueOf(slider.getValue()));
            onChange.accept(slider.getValue());
        });
        sliderPanel.add(slider, BorderLayout.CENTER);
        sliderPanel.add(valLabel, BorderLayout.EAST);

        c.gridx = 1; c.weightx = 0.7; c.gridwidth = 2;
        panel.add(sliderPanel, c);
        c.gridwidth = 1;
        return row + 1;
    }

    /** Adds a color row with buyer + seller color pickers. Returns next row. */
    private static int addColorRow(JPanel panel, GridBagConstraints c, int row,
                                   String label,
                                   Color buyerCurrent, Color buyerDefault,
                                   java.util.function.Consumer<Color> onBuyerChange,
                                   Color sellerCurrent, Color sellerDefault,
                                   java.util.function.Consumer<Color> onSellerChange) {
        c.gridx = 0; c.gridy = row; c.weightx = 0.3; c.gridwidth = 1;
        panel.add(new JLabel(label), c);

        ColorsConfigItem buyerColor = new ColorsConfigItem(buyerCurrent, buyerDefault, "", onBuyerChange::accept);
        c.gridx = 1; c.weightx = 0.35;
        panel.add(buyerColor, c);

        ColorsConfigItem sellerColor = new ColorsConfigItem(sellerCurrent, sellerDefault, "", onSellerChange::accept);
        c.gridx = 2; c.weightx = 0.35;
        panel.add(sellerColor, c);

        return row + 1;
    }

    /** Adds an aggregation row (checkbox + slider + value label). Returns next row. */
    private static int addAggRow(JPanel panel, GridBagConstraints c, int row,
                                 String label, boolean enabled, int value, int min, int max,
                                 AggChangeListener onChange) {
        JCheckBox check = new JCheckBox("<html>" + label.replace("\n", "<br>") + "</html>", enabled);
        c.gridx = 0; c.gridy = row; c.weightx = 0.3; c.gridwidth = 1;
        panel.add(check, c);

        JPanel sliderPanel = new JPanel(new BorderLayout(4, 0));
        sliderPanel.setOpaque(false);
        JSlider slider = new JSlider(min, max, value);
        JLabel valLabel = new JLabel(String.valueOf(value), SwingConstants.RIGHT);
        valLabel.setPreferredSize(new Dimension(35, valLabel.getPreferredSize().height));

        ChangeListener listener = e -> {
            valLabel.setText(String.valueOf(slider.getValue()));
            onChange.changed(check.isSelected(), slider.getValue());
        };
        slider.addChangeListener(listener);
        check.addActionListener(e -> onChange.changed(check.isSelected(), slider.getValue()));

        sliderPanel.add(slider, BorderLayout.CENTER);
        sliderPanel.add(valLabel, BorderLayout.EAST);

        c.gridx = 1; c.weightx = 0.7; c.gridwidth = 2;
        panel.add(sliderPanel, c);
        c.gridwidth = 1;
        return row + 1;
    }

    private static JLabel centeredLabel(String text) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    @FunctionalInterface
    private interface AggChangeListener {
        void changed(boolean enabled, int value);
    }
}

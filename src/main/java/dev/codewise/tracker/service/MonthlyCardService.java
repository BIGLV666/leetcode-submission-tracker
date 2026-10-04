package dev.codewise.tracker.service;

import dev.codewise.tracker.config.TrackerProperties;
import dev.codewise.tracker.repository.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 手账风月报分享卡片：把当月提交数据用 Java2D 画成 PNG，不依赖外部进程。
 * 版式与 scripts/monthly_report.py 中的手账卡片一致（奶油底、虚线框、暖色药丸）。
 */
@Service
public class MonthlyCardService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyCardService.class);
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int SCALE = 2;
    private static final int WIDTH = 1080;
    private static final int PADDING = 60;
    private static final int GAP = 12;
    private static final String FONT_NAME = "Microsoft YaHei";

    private static final Color BG = Color.decode("#fdf9f2");
    private static final Color INK = Color.decode("#4b3f33");
    private static final Color SUB_INK = Color.decode("#8a7a63");
    private static final Color MUTED = Color.decode("#b09a7c");
    private static final Color BORDER = Color.decode("#e5d5bb");
    private static final Color EMPTY_BORDER = Color.decode("#e8dcc6");
    private static final Color EMPTY_TEXT = Color.decode("#c4b190");
    private static final Color FOOT_INK = Color.decode("#b09a7c");
    private static final Color STUCK_BG = Color.decode("#fff4d6");
    private static final Color STUCK_BORDER = Color.decode("#eec253");
    private static final Color STUCK_META = Color.decode("#92600a");
    private static final Color[] CELL_FILLS = {
            Color.decode("#ffe4cc"), Color.decode("#ffc4a3"),
            Color.decode("#ff9d6b"), Color.decode("#ff6f4d")
    };
    private static final Color[] CELL_TEXTS = {
            Color.decode("#b45309"), Color.decode("#9a3412"),
            Color.decode("#ffffff"), Color.decode("#ffffff")
    };
    private static final Color[][] PILL_SCHEMES = {
            {Color.decode("#ffe9e2"), Color.decode("#ff8f70"), Color.decode("#c2410c")},
            {Color.decode("#fff3d6"), Color.decode("#eec253"), Color.decode("#92600a")},
            {Color.decode("#e8f3dd"), Color.decode("#95bd7a"), Color.decode("#3f6212")}
    };
    private static final Color[] LANG_COLORS = {
            Color.decode("#ff6f4d"), Color.decode("#f5b73d"), Color.decode("#7fb069"),
            Color.decode("#6fa8dc"), Color.decode("#c785c8"), Color.decode("#a5a5a5")
    };

    private final SubmissionRepository repository;
    private final TrackerProperties properties;

    public MonthlyCardService(SubmissionRepository repository, TrackerProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 已攻克 = 最后一次 AC 晚于最后一次失败提交。 */
    record StuckQuestion(String title, int fails, boolean solved) {
    }

    record CardData(
            int year,
            int month,
            int solved,
            int total,
            int activeDays,
            int daysInMonth,
            int firstTryRate,
            Map<LocalDate, Integer> daily,
            List<Map.Entry<String, Long>> languages,
            List<StuckQuestion> stuck
    ) {
    }

    public record CardFile(String fileName, Path path, int year, int month) {
    }

    /** 生成（或复用）指定月份的手账卡片 PNG。 */
    public CardFile generateMonthlyCard(int year, int month) throws IOException {
        Path output = cardPath(year, month);
        if (Files.exists(output)) {
            log.info("复用已生成的月报卡片 {}", output);
            return new CardFile(output.getFileName().toString(), output, year, month);
        }
        return renderMonthlyCard(year, month);
    }

    /** 强制重画并覆盖当月卡片。 */
    public CardFile renderMonthlyCard(int year, int month) throws IOException {
        CardData data = aggregate(year, month);
        BufferedImage image = drawCard(data);
        // 先在内存中编码完整字节再一次性写入，避免下载请求读到半截文件
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", buffer);
        Path output = cardPath(year, month);
        Files.createDirectories(output.getParent());
        Files.write(output, buffer.toByteArray());
        log.info("月报卡片已生成 {}", output);
        return new CardFile(output.getFileName().toString(), output, year, month);
    }

    public Path cardPath(int year, int month) {
        String fileName = "share-%04d-%02d.png".formatted(year, month);
        return Path.of(System.getProperty("user.dir"), "reports", fileName);
    }

    private CardData aggregate(int year, int month) {
        YearMonth reportMonth = YearMonth.of(year, month);
        LocalDateTime startUtc = LocalDateTime.ofInstant(
                reportMonth.atDay(1).atStartOfDay(REPORT_ZONE).toInstant(), ZoneOffset.UTC);
        LocalDateTime endUtc = LocalDateTime.ofInstant(
                reportMonth.plusMonths(1).atDay(1).atStartOfDay(REPORT_ZONE).toInstant(), ZoneOffset.UTC);
        List<SubmissionRepository.MonthlySubmission> rows =
                repository.findMonthlySubmissions(properties.userId(), startUtc, endUtc);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(year + " 年 " + month + " 月没有提交记录，先生成一张空卡片没有意义");
        }

        record QuestionStat(int subs, int accepted, int fails,
                            LocalDateTime lastAccepted, LocalDateTime lastFailed, String title) {
        }
        Map<String, QuestionStat> questions = new HashMap<>();
        Map<LocalDate, Integer> daily = new HashMap<>();
        Map<String, Long> languages = new LinkedHashMap<>();
        Set<LocalDate> activeDays = new HashSet<>();
        int acceptedTotal = 0;

        for (SubmissionRepository.MonthlySubmission row : rows) {
            LocalDate day = row.submittedAt().toInstant(ZoneOffset.UTC).atZone(REPORT_ZONE).toLocalDate();
            boolean accepted = "Accepted".equals(row.status());
            boolean compileError = "Compile Error".equals(row.status());
            daily.merge(day, 1, Integer::sum);
            languages.merge(row.language(), 1L, Long::sum);
            if (accepted) {
                activeDays.add(day);
                acceptedTotal++;
            }
            QuestionStat stat = questions.get(row.problemKey());
            if (stat == null) {
                stat = new QuestionStat(0, 0, 0, null, null, row.title());
            }
            LocalDateTime lastAccepted = accepted ? row.submittedAt() : stat.lastAccepted();
            LocalDateTime lastFailed = !accepted && !compileError ? row.submittedAt() : stat.lastFailed();
            questions.put(row.problemKey(), new QuestionStat(
                    stat.subs() + 1,
                    stat.accepted() + (accepted ? 1 : 0),
                    stat.fails() + (!accepted && !compileError ? 1 : 0),
                    lastAccepted, lastFailed, row.title()));
        }

        int solved = 0;
        int firstTry = 0;
        List<StuckQuestion> stuck = new ArrayList<>();
        for (QuestionStat stat : questions.values()) {
            if (stat.accepted() > 0) {
                solved++;
                if (stat.subs() == 1) {
                    firstTry++;
                }
            }
            if (stat.fails() > 0) {
                boolean ok = stat.accepted() > 0
                        && (stat.lastFailed() == null || stat.lastAccepted().isAfter(stat.lastFailed()));
                stuck.add(new StuckQuestion(stat.title(), stat.fails(), ok));
            }
        }
        stuck.sort(Comparator.comparingInt(StuckQuestion::fails).reversed()
                .thenComparing(StuckQuestion::title));
        List<Map.Entry<String, Long>> languageList = new ArrayList<>(languages.entrySet());
        languageList.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));

        int firstTryRate = solved == 0 ? 0 : (int) Math.round(firstTry * 100.0 / solved);
        return new CardData(year, month, solved, rows.size(), activeDays.size(),
                reportMonth.lengthOfMonth(), firstTryRate, daily, languageList,
                stuck.subList(0, Math.min(3, stuck.size())));
    }

    BufferedImage drawCard(CardData data) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D probeGraphics = probe.createGraphics();
        int legendRows = measureLegendRows(probeGraphics, data);
        probeGraphics.dispose();

        int calendarRows = calendarRows(data);
        int cellSize = (WIDTH - 2 * PADDING - 6 * GAP) / 7;
        int stuckRows = data.stuck().isEmpty() ? 1 : data.stuck().size();
        int height = 58          // 顶部留白
                + 66             // 标题
                + 44             // 副标题
                + 128 + 48       // 药丸
                + 34             // “打卡日历”小节标题
                + 24 + calendarRows * (cellSize + GAP)  // 星期行 + 日历
                + 26 + 34 + 42 + 20 + legendRows * 32   // 语言占比
                + 30 + 34 + stuckRows * (62 + 14)       // 最难缠的题
                + 40 + 22 + 28   // 页脚
                + 52;            // 底部留白

        BufferedImage image = new BufferedImage(WIDTH * SCALE, height * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.scale(SCALE, SCALE);
        g2.setFont(font(false, 20));
        FontMetrics metrics = g2.getFontMetrics();

        paintCard(g2, data, metrics, cellSize, calendarRows, legendRows, height);

        g2.dispose();
        return image;
    }

    private void paintCard(Graphics2D g2, CardData data, FontMetrics metrics,
                           int cellSize, int calendarRows, int legendRows, int height) {
        g2.setColor(BG);
        g2.fillRect(0, 0, WIDTH, height);

        // 外圈虚线框
        g2.setColor(BORDER);
        g2.setStroke(dashStroke(2f));
        g2.draw(new RoundRectangle2D.Float(8, 8, WIDTH - 16, height - 16, 56, 56));

        int y = 58;
        // 标题行：左标题 + 右年月
        g2.setColor(INK);
        g2.setFont(font(true, 50));
        g2.drawString(monthTitle(data), PADDING, y + 48);
        g2.setColor(MUTED);
        g2.setFont(font(true, 24));
        String when = "%04d.%02d".formatted(data.year(), data.month());
        g2.drawString(when, WIDTH - PADDING - metrics.stringWidth(when), y + 48);

        y += 66;
        g2.setColor(SUB_INK);
        g2.setFont(font(false, 23));
        g2.drawString("打卡 %d/%d 天 · 提交 %d 次 · 都是很棒的努力"
                .formatted(data.activeDays(), data.daysInMonth(), data.total()), PADDING, y + 20);

        // 三个统计药丸
        y += 44;
        int pillWidth = (WIDTH - 2 * PADDING - 2 * 20) / 3;
        String[] pillNumbers = {
                String.valueOf(data.solved()),
                String.valueOf(data.total()),
                data.firstTryRate() + "%"
        };
        String[] pillLabels = {"题 AC", "次提交", "一发即中"};
        for (int i = 0; i < 3; i++) {
            int x = PADDING + i * (pillWidth + 20);
            Color[] scheme = PILL_SCHEMES[i];
            g2.setColor(scheme[0]);
            g2.fill(new RoundRectangle2D.Float(x, y, pillWidth, 128, 128, 128));
            g2.setColor(scheme[1]);
            g2.setStroke(dashStroke(2f));
            g2.draw(new RoundRectangle2D.Float(x, y, pillWidth, 128, 128, 128));
            g2.setColor(scheme[2]);
            g2.setFont(font(true, 54));
            drawCentered(g2, pillNumbers[i], x + pillWidth / 2f, y + 66);
            g2.setFont(font(true, 21));
            drawCentered(g2, pillLabels[i], x + pillWidth / 2f, y + 104);
        }

        // 打卡日历
        y += 128 + 48;
        sectionTitle(g2, "打卡日历", y);
        y += 34;
        g2.setColor(EMPTY_TEXT);
        g2.setFont(font(true, 18));
        String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
        float cellWidth = cellSize;
        for (int i = 0; i < 7; i++) {
            float cx = PADDING + i * (cellWidth + GAP) + cellWidth / 2f;
            drawCentered(g2, weekdays[i], cx, y + 14);
        }
        y += 24;
        int firstWeekday = LocalDate.of(data.year(), data.month(), 1).getDayOfWeek().getValue() - 1;
        for (int index = 0; index < firstWeekday + data.daysInMonth(); index++) {
            int row = index / 7;
            int column = index % 7;
            int day = index - firstWeekday + 1;
            float x = PADDING + column * (cellWidth + GAP);
            float cellY = y + row * (cellSize + GAP);
            if (day < 1) {
                continue;
            }
            int subs = data.daily().getOrDefault(LocalDate.of(data.year(), data.month(), day), 0);
            if (subs == 0) {
                g2.setColor(EMPTY_BORDER);
                g2.setStroke(dashStroke(2f));
                g2.draw(new RoundRectangle2D.Float(x, cellY, cellSize, cellSize, 32, 32));
                g2.setColor(EMPTY_TEXT);
                g2.setFont(font(false, 19));
            } else {
                int level = subs <= 2 ? 0 : subs <= 5 ? 1 : subs <= 8 ? 2 : 3;
                g2.setColor(CELL_FILLS[level]);
                g2.fill(new RoundRectangle2D.Float(x, cellY, cellSize, cellSize, 32, 32));
                g2.setColor(CELL_TEXTS[level]);
                g2.setFont(level >= 3 ? font(true, 19) : font(false, 19));
            }
            drawCentered(g2, String.valueOf(day), x + cellWidth / 2f, cellY + cellSize / 2f + 7);
        }
        y += calendarRows * (cellSize + GAP);

        // 语言占比
        y += 26;
        sectionTitle(g2, "语言占比", y);
        y += 34;
        paintLanguageBar(g2, data, y);
        y += 42 + 20;
        y += paintLegend(g2, data, y);

        // 最难缠的题
        y += 30;
        sectionTitle(g2, "最难缠的题", y);
        y += 34;
        if (data.stuck().isEmpty()) {
            g2.setColor(SUB_INK);
            g2.setFont(font(false, 21));
            g2.drawString("本月零卡壳，太稳了", PADDING + 22, y + 40);
            y += 62;
        } else {
            for (StuckQuestion question : data.stuck()) {
                g2.setColor(STUCK_BG);
                g2.fill(new RoundRectangle2D.Float(PADDING, y, WIDTH - 2 * PADDING, 62, 32, 32));
                g2.setColor(STUCK_BORDER);
                g2.setStroke(dashStroke(2f));
                g2.draw(new RoundRectangle2D.Float(PADDING, y, WIDTH - 2 * PADDING, 62, 32, 32));
                g2.setColor(INK);
                g2.setFont(font(true, 22));
                g2.drawString(question.title(), PADDING + 24, y + 39);
                g2.setColor(STUCK_META);
                g2.setFont(font(false, 18));
                String meta = "失败 %d 次 · %s".formatted(question.fails(), question.solved() ? "已攻克" : "继续加油");
                g2.drawString(meta, WIDTH - PADDING - 24 - metrics.stringWidth(meta), y + 39);
                y += 62 + 14;
            }
            y -= 14;
        }

        // 页脚
        y += 40;
        g2.setColor(BORDER);
        g2.setStroke(dashStroke(2f));
        g2.drawLine(PADDING, y, WIDTH - PADDING, y);
        y += 22;
        g2.setColor(FOOT_INK);
        g2.setFont(font(false, 20));
        g2.drawString("下个月也要天天打卡鸭", PADDING, y + 16);
    }

    private void paintLanguageBar(Graphics2D g2, CardData data, int y) {
        float barWidth = WIDTH - 2 * PADDING;
        float[] fractions = new float[data.languages().size()];
        long total = 0;
        for (Map.Entry<String, Long> entry : data.languages()) {
            total += entry.getValue();
        }
        for (int i = 0; i < data.languages().size(); i++) {
            fractions[i] = (float) (data.languages().get(i).getValue() * 1.0 / total);
        }
        RoundRectangle2D clip = new RoundRectangle2D.Float(PADDING, y, barWidth, 42, 42, 42);
        float offset = 0;
        for (int i = 0; i < data.languages().size(); i++) {
            float w = fractions[i] * barWidth;
            g2.setClip(clip);
            g2.setColor(LANG_COLORS[i % LANG_COLORS.length]);
            g2.fill(new RoundRectangle2D.Float(PADDING + offset, y, w, 42, 0, 0));
            if (fractions[i] >= 0.18) {
                g2.setColor(Color.WHITE);
                g2.setFont(font(true, 18));
                drawCentered(g2, langLabel(data.languages().get(i).getKey()) + " " + Math.round(fractions[i] * 100) + "%",
                        PADDING + offset + w / 2f, y + 27);
            }
            offset += w;
        }
        g2.setClip(null);
        g2.setColor(BORDER);
        g2.setStroke(dashStroke(2f));
        g2.draw(clip);
    }

    private int paintLegend(Graphics2D g2, CardData data, int y) {
        long total = 0;
        for (Map.Entry<String, Long> entry : data.languages()) {
            total += entry.getValue();
        }
        Font labelFont = font(true, 20);
        Font countFont = font(false, 17);
        FontMetrics labelMetrics = g2.getFontMetrics(labelFont);
        FontMetrics countMetrics = g2.getFontMetrics(countFont);
        int x = PADDING;
        int rows = 1;
        for (int i = 0; i < data.languages().size(); i++) {
            Map.Entry<String, Long> entry = data.languages().get(i);
            long count = entry.getValue();
            int percent = (int) Math.round(count * 100.0 / total);
            String label = langLabel(entry.getKey());
            String countText = count + " 次 · " + percent + "%";
            int itemWidth = 14 + 9 + labelMetrics.stringWidth(label) + 8 + countMetrics.stringWidth(countText);
            if (x + itemWidth > WIDTH - PADDING) {
                x = PADDING;
                rows++;
            }
            g2.setColor(LANG_COLORS[i % LANG_COLORS.length]);
            g2.fillOval(x, y + rows * 32 - 22, 14, 14);
            x += 14 + 9;
            g2.setColor(INK);
            g2.setFont(labelFont);
            g2.drawString(label, x, y + rows * 32 - 10);
            x += labelMetrics.stringWidth(label) + 8;
            g2.setColor(MUTED);
            g2.setFont(countFont);
            g2.drawString(countText, x, y + rows * 32 - 10);
            x += countMetrics.stringWidth(countText) + 28;
        }
        return rows * 32;
    }

    private int measureLegendRows(Graphics2D g2, CardData data) {
        long total = 0;
        for (Map.Entry<String, Long> entry : data.languages()) {
            total += entry.getValue();
        }
        Font labelFont = font(true, 20);
        Font countFont = font(false, 17);
        FontMetrics labelMetrics = g2.getFontMetrics(labelFont);
        FontMetrics countMetrics = g2.getFontMetrics(countFont);
        int x = PADDING;
        int rows = 1;
        for (int i = 0; i < data.languages().size(); i++) {
            Map.Entry<String, Long> entry = data.languages().get(i);
            String label = langLabel(entry.getKey());
            String countText = entry.getValue() + " 次 · " + Math.round(entry.getValue() * 100.0 / total) + "%";
            int itemWidth = 14 + 9 + labelMetrics.stringWidth(label) + 8 + countMetrics.stringWidth(countText);
            if (x + itemWidth > WIDTH - PADDING) {
                x = PADDING;
                rows++;
            }
            x += itemWidth + 28;
        }
        return rows;
    }

    private void sectionTitle(Graphics2D g2, String title, int y) {
        g2.setColor(INK);
        g2.setFont(font(true, 22));
        g2.drawString(title, PADDING, y + 18);
    }

    private void drawCentered(Graphics2D g2, String text, float centerX, float baseline) {
        FontMetrics fontMetrics = g2.getFontMetrics();
        g2.drawString(text, centerX - fontMetrics.stringWidth(text) / 2f, baseline);
    }

    private Stroke dashStroke(float width) {
        return new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{9f, 7f}, 0f);
    }

    private Font font(boolean bold, int size) {
        return new Font(FONT_NAME, bold ? Font.BOLD : Font.PLAIN, size);
    }

    private String monthTitle(CardData data) {
        return data.month() + " 月刷题手账";
    }

    private String langLabel(String language) {
        return switch (language) {
            case "golang" -> "Go";
            case "java" -> "Java";
            case "python3" -> "Python";
            case "mysql" -> "MySQL";
            case "cpp" -> "C++";
            default -> language;
        };
    }

    private int calendarRows(CardData data) {
        int firstWeekday = LocalDate.of(data.year(), data.month(), 1).getDayOfWeek().getValue() - 1;
        return (firstWeekday + data.daysInMonth() + 6) / 7;
    }
}

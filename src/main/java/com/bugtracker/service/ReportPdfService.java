package com.bugtracker.service;

import com.bugtracker.dto.ReportResponse;
import com.bugtracker.dto.ReportResponse.DurationStat;
import com.bugtracker.dto.ReportResponse.GroupStat;
import com.bugtracker.dto.ReportResponse.WeekStat;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Renders a {@link ReportResponse} as an A4 PDF: summary figures, an opened-vs-closed chart and tables. */
@Service
public class ReportPdfService {

    // Same series colours as the Reports page (validated categorical slots 1 and 2).
    private static final Color OPENED = new Color(0x2a78d6);
    private static final Color CLOSED = new Color(0xeb6834);
    private static final Color INK = new Color(0x1d2330);
    private static final Color MUTED = new Color(0x6b7385);
    private static final Color GRID = new Color(0xe1e0d9);
    private static final Color HEADER_FILL = new Color(0xf0f2f5);
    private static final Color BORDER = new Color(0xe2e5ea);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private final Font title = new Font(Font.HELVETICA, 18, Font.BOLD, INK);
    private final Font heading = new Font(Font.HELVETICA, 12, Font.BOLD, INK);
    private final Font body = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
    private final Font bodyBold = new Font(Font.HELVETICA, 9, Font.BOLD, INK);
    private final Font muted = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
    private final Font figure = new Font(Font.HELVETICA, 16, Font.BOLD, INK);

    public byte[] render(ReportResponse r) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 40, 40);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            doc.addTitle("BugTrack report");
            doc.addCreator("BugTrack");
            doc.open();

            doc.add(new Paragraph("BugTrack report", title));
            String scope = r.projectName() != null ? "Project: " + r.projectName() : "All projects you can access";
            doc.add(new Paragraph(scope + "  |  Last " + r.weeks() + " weeks: " + r.from().format(DAY) + " to "
                    + r.to().format(DAY), body));
            doc.add(new Paragraph("Generated " + STAMP.format(r.generatedAt().atZone(ZoneId.systemDefault()))
                    + " for " + r.generatedFor(), muted));

            doc.add(spacer(10));
            doc.add(summaryTable(r));

            section(doc, "Bugs opened and closed per week");
            doc.add(chart(writer, r.weekly()));

            section(doc, "Weekly figures");
            PdfPTable weekly = table(new float[]{3, 2, 2, 2}, "Week starting", "Opened", "Closed", "Open at end");
            for (WeekStat w : r.weekly()) {
                row(weekly, w.weekStart().format(DAY), num(w.opened()), num(w.closed()), num(w.openAtEnd()));
            }
            doc.add(weekly);

            section(doc, "By project (all bugs, current status)");
            doc.add(groupTable("Project", r.byProject()));

            section(doc, "By assignee (all bugs, current status)");
            doc.add(groupTable("Assignee", r.byAssignee()));

            doc.add(spacer(8));
            doc.add(new Paragraph("Open means not closed: open, in progress, or fixed and awaiting testing. "
                    + "Time to fix is from report to first marked fixed; time to close is from report to closed. "
                    + "Both count bugs fixed or closed during the period. A bug that was reopened and closed again "
                    + "counts each time it was closed in the weekly figures.", muted));
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not create the PDF report", e);
        }
        return out.toByteArray();
    }

    private PdfPTable summaryTable(ReportResponse r) {
        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        summaryCell(t, num(r.openedInPeriod()), "Opened in period");
        summaryCell(t, num(r.closedInPeriod()), "Closed in period");
        summaryCell(t, num(r.openBugs()), "Open now (of " + r.totalBugs() + ")");
        summaryCell(t, duration(r.timeToClose()), "Average time to close" + median(r.timeToClose()));
        summaryCell(t, num(r.totalBugs()), "Bugs in total");
        summaryCell(t, num(r.closedBugs()), "Closed in total");
        summaryCell(t, num(r.timeToFix().count()), "Fixed in period");
        summaryCell(t, duration(r.timeToFix()), "Average time to fix" + median(r.timeToFix()));
        return t;
    }

    private void summaryCell(PdfPTable t, String value, String label) {
        PdfPCell cell = new PdfPCell();
        Paragraph number = new Paragraph(value, figure);
        number.setLeading(19);
        Paragraph caption = new Paragraph(label, muted);
        caption.setLeading(11);
        caption.setSpacingBefore(3);
        cell.addElement(number);
        cell.addElement(caption);
        cell.setPaddingTop(4);
        cell.setPaddingBottom(9);
        cell.setPaddingLeft(8);
        cell.setPaddingRight(8);
        cell.setBorderColor(BORDER);
        t.addCell(cell);
    }

    private PdfPTable groupTable(String nameHeader, List<GroupStat> groups) {
        PdfPTable t = table(new float[]{4, 2, 2, 2, 3}, nameHeader, "Total", "Open", "Closed", "Avg days to close");
        if (groups.isEmpty()) {
            PdfPCell none = new PdfPCell(new Phrase("No bugs", muted));
            none.setColspan(5);
            none.setPadding(5);
            none.setBorderColor(BORDER);
            t.addCell(none);
        }
        for (GroupStat g : groups) {
            row(t, g.name(), num(g.total()), num(g.open()), num(g.closed()),
                    g.averageDaysToClose() == null ? "-" : String.format(Locale.ENGLISH, "%.1f", g.averageDaysToClose()));
        }
        return t;
    }

    /** Grouped bars: opened (blue) and closed (orange) per week, on one axis starting at zero. */
    private Image chart(PdfWriter writer, List<WeekStat> weeks) throws DocumentException {
        float width = 515;
        float height = 190;
        float left = 30, right = 8, bottom = 34, top = 22;
        float plotW = width - left - right;
        float plotH = height - top - bottom;
        long max = weeks.stream().mapToLong(w -> Math.max(w.opened(), w.closed())).max().orElse(0);
        long step = niceStep(max);
        long axisMax = Math.max(step * 4, ((max + step - 1) / step) * step);

        PdfTemplate tpl = writer.getDirectContent().createTemplate(width, height);
        BaseFont font = baseFont();

        // Gridlines and y-axis labels.
        tpl.setLineWidth(0.5f);
        for (long v = 0; v <= axisMax; v += step) {
            float y = bottom + plotH * v / axisMax;
            tpl.setColorStroke(v == 0 ? MUTED : GRID);
            tpl.moveTo(left, y);
            tpl.lineTo(left + plotW, y);
            tpl.stroke();
            text(tpl, font, 7, MUTED, PdfContentByte.ALIGN_RIGHT, String.valueOf(v), left - 4, y - 2.5f);
        }

        // Bars.
        int n = weeks.size();
        float group = plotW / n;
        float bar = Math.min(10, (group - 4) / 2);
        int labelEvery = Math.max(1, (int) Math.ceil(n / 8.0));
        for (int i = 0; i < n; i++) {
            WeekStat w = weeks.get(i);
            float cx = left + group * i + group / 2;
            bar(tpl, cx - bar - 1, bottom, bar, plotH * w.opened() / axisMax, OPENED);
            bar(tpl, cx + 1, bottom, bar, plotH * w.closed() / axisMax, CLOSED);
            if (i == n - 1 || (i % labelEvery == 0 && n - 1 - i >= labelEvery)) { // keep labels apart from the last one
                text(tpl, font, 7, MUTED, PdfContentByte.ALIGN_CENTER, w.weekStart().format(SHORT_DAY), cx, bottom - 12);
            }
        }

        // Legend.
        legend(tpl, font, left, height - 10, OPENED, "Opened");
        legend(tpl, font, left + 70, height - 10, CLOSED, "Closed");

        Image image = Image.getInstance(tpl);
        image.setAlignment(Element.ALIGN_CENTER);
        return image;
    }

    /** One filled shape: square corners on the baseline, rounded top corners. */
    private static void bar(PdfTemplate tpl, float x, float y, float w, float h, Color color) {
        if (h <= 0) {
            return;
        }
        float r = Math.min(2, Math.min(w / 2, h));
        float top = y + h;
        float k = 0.5523f * r; // Bezier handle length for a quarter circle
        tpl.setColorFill(color);
        tpl.moveTo(x, y);
        tpl.lineTo(x, top - r);
        tpl.curveTo(x, top - r + k, x + r - k, top, x + r, top);
        tpl.lineTo(x + w - r, top);
        tpl.curveTo(x + w - r + k, top, x + w, top - r + k, x + w, top - r);
        tpl.lineTo(x + w, y);
        tpl.closePath();
        tpl.fill();
    }

    private static void legend(PdfTemplate tpl, BaseFont font, float x, float y, Color color, String label) {
        tpl.setColorFill(color);
        tpl.roundRectangle(x, y - 1, 8, 8, 1.5f);
        tpl.fill();
        text(tpl, font, 8, INK, PdfContentByte.ALIGN_LEFT, label, x + 12, y);
    }

    private static void text(PdfTemplate tpl, BaseFont font, float size, Color color, int align, String s, float x, float y) {
        tpl.beginText();
        tpl.setColorFill(color);
        tpl.setFontAndSize(font, size);
        tpl.showTextAligned(align, s, x, y, 0);
        tpl.endText();
    }

    /** A round step (1, 2, 5, 10, 20, ...) giving about four gridlines. */
    static long niceStep(long max) {
        if (max <= 4) {
            return 1;
        }
        double raw = max / 4.0;
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double[] candidates = {1, 2, 5, 10};
        for (double c : candidates) {
            if (c * magnitude >= raw) {
                return (long) (c * magnitude);
            }
        }
        return (long) (10 * magnitude);
    }

    private PdfPTable table(float[] widths, String... headers) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (int i = 0; i < headers.length; i++) {
            PdfPCell c = new PdfPCell(new Phrase(headers[i], bodyBold));
            c.setBackgroundColor(HEADER_FILL);
            c.setBorderColor(BORDER);
            c.setPadding(5);
            c.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            t.addCell(c);
        }
        return t;
    }

    private void row(PdfPTable t, String... values) {
        for (int i = 0; i < values.length; i++) {
            PdfPCell c = new PdfPCell(new Phrase(values[i], body));
            c.setBorderColor(BORDER);
            c.setPadding(5);
            c.setHorizontalAlignment(i == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            t.addCell(c);
        }
    }

    private void section(Document doc, String text) throws DocumentException {
        Paragraph p = new Paragraph(text, heading);
        p.setSpacingBefore(14);
        p.setSpacingAfter(6);
        doc.add(p);
    }

    private static Paragraph spacer(float points) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(points);
        return p;
    }

    private static String num(long n) {
        return String.format(Locale.ENGLISH, "%,d", n);
    }

    private static String duration(DurationStat s) {
        return s.averageDays() == null ? "-" : String.format(Locale.ENGLISH, "%.1f days", s.averageDays());
    }

    private static String median(DurationStat s) {
        return s.medianDays() == null ? "" : String.format(Locale.ENGLISH, " (median %.1f)", s.medianDays());
    }

    private static BaseFont baseFont() {
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (DocumentException e) {
            throw new IllegalStateException(e);
        }
    }
}

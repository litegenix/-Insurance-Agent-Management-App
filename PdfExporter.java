package com.insuranceagent.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.os.Environment;
import android.util.Log;

import com.insuranceagent.models.Payment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * PdfExporter — generates PDF reports on-device using Android's built-in PdfDocument API.
 * No external library needed for basic reports.
 * For complex formatting, integrate iText7 library.
 */
public class PdfExporter {

    private static final String TAG        = "PdfExporter";
    private static final int    PAGE_WIDTH  = 595;   // A4 width in points
    private static final int    PAGE_HEIGHT = 842;   // A4 height in points
    private static final int    MARGIN      = 40;
    private static final int    LINE_HEIGHT = 20;

    // ─── Generate Payment Report PDF ──────────────────────────────────────────
    public static File generatePaymentReport(Context context,
                                              List<Payment> payments,
                                              String agentName,
                                              String period) throws IOException {
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                PAGE_WIDTH, PAGE_HEIGHT, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);

        Canvas canvas = page.getCanvas();
        int y = MARGIN + 20;

        // Title
        Paint titlePaint = new Paint();
        titlePaint.setColor(Color.parseColor("#1565C0"));
        titlePaint.setTextSize(20f);
        titlePaint.setFakeBoldText(true);
        canvas.drawText("Insurance Agent Payment Report", MARGIN, y, titlePaint);
        y += 30;

        // Subtitle
        Paint subPaint = new Paint();
        subPaint.setColor(Color.DKGRAY);
        subPaint.setTextSize(12f);
        canvas.drawText("Agent: " + agentName + "  |  Period: " + period, MARGIN, y, subPaint);
        y += 10;
        canvas.drawText("Generated: " + new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                .format(new Date()), MARGIN, y, subPaint);
        y += 20;

        // Divider line
        Paint linePaint = new Paint();
        linePaint.setColor(Color.LTGRAY);
        linePaint.setStrokeWidth(1f);
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint);
        y += 15;

        // Table Header
        Paint headerPaint = new Paint();
        headerPaint.setColor(Color.WHITE);
        headerPaint.setTextSize(10f);
        headerPaint.setFakeBoldText(true);

        Paint headerBgPaint = new Paint();
        headerBgPaint.setColor(Color.parseColor("#1976D2"));
        canvas.drawRect(MARGIN, y - 12, PAGE_WIDTH - MARGIN, y + 6, headerBgPaint);
        canvas.drawText("Client Name",     MARGIN + 5,        y, headerPaint);
        canvas.drawText("Policy No.",      MARGIN + 150,      y, headerPaint);
        canvas.drawText("Amount (PKR)",    MARGIN + 270,      y, headerPaint);
        canvas.drawText("Status",          MARGIN + 380,      y, headerPaint);
        canvas.drawText("Date",            MARGIN + 450,      y, headerPaint);
        y += 20;

        // Table Rows
        Paint rowPaint     = new Paint();
        rowPaint.setTextSize(9f);

        Paint rowBgAlt = new Paint();
        rowBgAlt.setColor(Color.parseColor("#F5F5F5"));

        Paint statusPaint = new Paint();
        statusPaint.setTextSize(9f);

        double totalCollected = 0;
        double totalDeposited = 0;

        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault());

        for (int i = 0; i < payments.size(); i++) {
            Payment p = payments.get(i);

            // Alternate row background
            if (i % 2 == 0) {
                canvas.drawRect(MARGIN, y - 12, PAGE_WIDTH - MARGIN, y + 5, rowBgAlt);
            }

            // Status colour
            switch (p.getStatus() != null ? p.getStatus() : "") {
                case "Deposited": statusPaint.setColor(Color.parseColor("#388E3C")); break;
                case "Collected": statusPaint.setColor(Color.parseColor("#1976D2")); break;
                case "Overdue":   statusPaint.setColor(Color.parseColor("#D32F2F")); break;
                default:          statusPaint.setColor(Color.parseColor("#F57C00")); break;
            }

            rowPaint.setColor(Color.DKGRAY);
            canvas.drawText(truncate(p.getClientName(), 20), MARGIN + 5, y, rowPaint);
            canvas.drawText(truncate(p.getPolicyNumber(), 15), MARGIN + 150, y, rowPaint);
            canvas.drawText(String.format("%.0f", p.getAmountCollected()), MARGIN + 270, y, rowPaint);
            canvas.drawText(p.getStatus() != null ? p.getStatus() : "-", MARGIN + 380, y, statusPaint);
            canvas.drawText(p.getCollectionDate() != null ? sdf.format(p.getCollectionDate()) : "-",
                    MARGIN + 450, y, rowPaint);

            totalCollected += p.getAmountCollected();
            totalDeposited += p.getAmountDeposited();

            y += LINE_HEIGHT;

            // Start new page if running out of space
            if (y > PAGE_HEIGHT - 80) {
                document.finishPage(page);
                pageInfo = new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT,
                        document.getPages().size() + 1).create();
                page = document.startPage(pageInfo);
                canvas = page.getCanvas();
                y = MARGIN + 20;
            }
        }

        // Summary Footer
        y += 10;
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, linePaint);
        y += 15;

        Paint summaryPaint = new Paint();
        summaryPaint.setColor(Color.parseColor("#1565C0"));
        summaryPaint.setTextSize(11f);
        summaryPaint.setFakeBoldText(true);
        canvas.drawText("Total Collected:  PKR " + String.format("%,.0f", totalCollected),
                MARGIN, y, summaryPaint);
        y += LINE_HEIGHT;
        canvas.drawText("Total Deposited:  PKR " + String.format("%,.0f", totalDeposited),
                MARGIN, y, summaryPaint);
        y += LINE_HEIGHT;
        canvas.drawText("Pending Deposit:  PKR " + String.format("%,.0f", totalCollected - totalDeposited),
                MARGIN, y, summaryPaint);

        document.finishPage(page);

        // Save to Downloads folder
        File outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        String fileName = "PaymentReport_" +
                new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date()) + ".pdf";
        File outputFile = new File(outputDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            document.writeTo(fos);
        } finally {
            document.close();
        }

        Log.d(TAG, "PDF saved: " + outputFile.getAbsolutePath());
        return outputFile;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "-";
        return text.length() > maxLen ? text.substring(0, maxLen - 2) + ".." : text;
    }
}

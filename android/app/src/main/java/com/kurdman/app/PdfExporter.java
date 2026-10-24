package com.kurdman.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Creates a readable A4 PDF copy of rendered manhwa pages. */
final class PdfExporter {
    private static final int A4_WIDTH = 595;
    private static final int A4_HEIGHT = 842;
    private static final int MARGIN = 24;
    private static final int MAX_PDF_PAGES = 3000;

    interface Progress {
        void onProgress(int completedSources, int totalSources, int completedPdfPages)
                throws InterruptedException;
    }

    private PdfExporter() {
    }

    static int writeBitmap(Bitmap bitmap, File outputFile, Progress progress)
            throws IOException, InterruptedException {
        if (bitmap == null || bitmap.isRecycled()) {
            throw new IOException("No rendered image is available for the PDF");
        }
        Writer writer = new Writer(outputFile, 1, progress);
        try {
            writer.addBitmap(bitmap, 0);
            return writer.finish();
        } catch (IOException | InterruptedException | RuntimeException e) {
            writer.abort();
            throw e;
        }
    }

    static int writeImageFiles(List<File> files, File outputFile, Progress progress)
            throws IOException, InterruptedException {
        if (files == null || files.isEmpty()) {
            throw new IOException("No rendered pages are available for the PDF");
        }
        Writer writer = new Writer(outputFile, files.size(), progress);
        try {
            int decodedSources = 0;
            for (int i = 0; i < files.size(); i++) {
                File file = files.get(i);
                Bitmap bitmap = BitmapIo.decodeFile(file);
                if (bitmap == null) {
                    // Keep going: one unreadable kept page should not prevent
                    // a PDF of the rest of the chapter from being made.
                    writer.reportSourceCompleted(i + 1);
                    continue;
                }
                try {
                    writer.addBitmap(bitmap, i);
                    decodedSources++;
                } finally {
                    bitmap.recycle();
                }
            }
            if (decodedSources == 0) {
                throw new IOException(
                        "None of the rendered pages could be read for the PDF");
            }
            return writer.finish();
        } catch (IOException | InterruptedException | RuntimeException e) {
            writer.abort();
            throw e;
        }
    }

    private static final class Writer {
        private final PdfDocument document = new PdfDocument();
        private final Paint bitmapPaint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final File outputFile;
        private final int totalSources;
        private final Progress progress;
        private int completedPdfPages;
        private boolean closed;

        Writer(File outputFile, int totalSources, Progress progress) throws IOException {
            if (outputFile == null) {
                throw new IOException("PDF output file is missing");
            }
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Could not create the PDF output directory");
            }
            this.outputFile = outputFile;
            this.totalSources = Math.max(1, totalSources);
            this.progress = progress;
        }

        void addBitmap(Bitmap bitmap, int sourceIndex)
                throws IOException, InterruptedException {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width <= 0 || height <= 0) {
                throw new IOException("Rendered page has invalid dimensions");
            }

            int portraitContentWidth = A4_WIDTH - (2 * MARGIN);
            int portraitContentHeight = A4_HEIGHT - (2 * MARGIN);
            double sourceRatio = (double) height / (double) width;
            double portraitRatio = (double) portraitContentHeight
                    / (double) portraitContentWidth;

            if (sourceRatio > portraitRatio) {
                // A long webtoon strip would become unreadably small if it
                // were squeezed onto one A4 sheet. Cut it into successive
                // full-width A4 pages instead.
                int sliceHeight = Math.max(1, (int) Math.floor(
                        width * portraitRatio));
                int slices = (height + sliceHeight - 1) / sliceHeight;
                ensurePageBudget(slices);
                for (int slice = 0; slice < slices; slice++) {
                    int top = slice * sliceHeight;
                    int bottom = Math.min(height, top + sliceHeight);
                    Rect source = new Rect(0, top, width, bottom);
                    float scale = portraitContentWidth / (float) width;
                    float destHeight = Math.max(1f, source.height() * scale);
                    RectF dest = new RectF(
                            MARGIN,
                            MARGIN,
                            MARGIN + portraitContentWidth,
                            MARGIN + destHeight);
                    drawPage(bitmap, source, A4_WIDTH, A4_HEIGHT, dest,
                            sourceIndex + 1);
                }
            } else {
                ensurePageBudget(1);
                boolean landscape = width > height;
                int pageWidth = landscape ? A4_HEIGHT : A4_WIDTH;
                int pageHeight = landscape ? A4_WIDTH : A4_HEIGHT;
                int contentWidth = pageWidth - (2 * MARGIN);
                int contentHeight = pageHeight - (2 * MARGIN);
                float scale = Math.min(
                        contentWidth / (float) width,
                        contentHeight / (float) height);
                float destWidth = Math.max(1f, width * scale);
                float destHeight = Math.max(1f, height * scale);
                float left = (pageWidth - destWidth) / 2f;
                float top = (pageHeight - destHeight) / 2f;
                RectF dest = new RectF(
                        left, top, left + destWidth, top + destHeight);
                drawPage(bitmap, new Rect(0, 0, width, height),
                        pageWidth, pageHeight, dest, sourceIndex + 1);
            }
        }

        int finish() throws IOException {
            boolean success = false;
            try {
                if (completedPdfPages <= 0) {
                    throw new IOException("The PDF would contain no pages");
                }
                OutputStream out = new BufferedOutputStream(
                        new FileOutputStream(outputFile));
                try {
                    document.writeTo(out);
                    out.flush();
                } finally {
                    out.close();
                }
                if (!outputFile.isFile() || outputFile.length() <= 0) {
                    throw new IOException("The PDF file was not written");
                }
                success = true;
                return completedPdfPages;
            } finally {
                closeDocument();
                if (!success && outputFile.exists()) {
                    // Never leave a truncated file behind that looks like a
                    // finished PDF in Downloads.
                    outputFile.delete();
                }
            }
        }

        void abort() {
            closeDocument();
            if (outputFile.exists()) {
                outputFile.delete();
            }
        }

        void reportSourceCompleted(int completedSources) throws InterruptedException {
            if (progress != null) {
                progress.onProgress(Math.min(completedSources, totalSources),
                        totalSources, completedPdfPages);
            }
        }

        private void drawPage(Bitmap bitmap, Rect source,
                              int pageWidth, int pageHeight,
                              RectF dest, int completedSources)
                throws InterruptedException {
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                    pageWidth, pageHeight, completedPdfPages + 1).create();
            PdfDocument.Page page = document.startPage(info);
            try {
                Canvas canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(bitmap, source, dest, bitmapPaint);
            } finally {
                document.finishPage(page);
            }
            completedPdfPages++;
            if (progress != null) {
                progress.onProgress(Math.min(completedSources, totalSources),
                        totalSources, completedPdfPages);
            }
        }

        private void ensurePageBudget(int additionalPages) throws IOException {
            if (completedPdfPages + additionalPages > MAX_PDF_PAGES) {
                throw new IOException("PDF page limit exceeded (" + MAX_PDF_PAGES + ")");
            }
        }

        private void closeDocument() {
            if (!closed) {
                closed = true;
                try {
                    document.close();
                } catch (RuntimeException ignored) {
                    // Closing a failed native document must not mask the
                    // original error reported to the caller.
                }
            }
        }
    }
}

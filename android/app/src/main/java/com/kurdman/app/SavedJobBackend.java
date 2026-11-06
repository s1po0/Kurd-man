package com.kurdman.app;
import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Existing OCR/translator/renderer routes; no new model or paid provider. */
final class SavedJobBackend implements SavedJobPipeline.Backend {
    private final Context context;
    private final Consumer<String> status;
    private volatile PaddleOcrClient paddle;
    private volatile SoraniTranslator translator;
    private volatile boolean cancelled;
    SavedJobBackend(Context context,Consumer<String> status){this.context=context;this.status=status;}
    public java.util.List<Segment> recognize(Bitmap bitmap,TranslationProject p) throws Exception {
        if(cancelled)throw new InterruptedException(JobRuntime.STOPPED_MESSAGE);
        if(paddle==null&&p.accurateOcr&&new PaddleOcrModelManager(context).isReadyFor(p.sourceLanguage)) {
            PaddleOcrClient client=new PaddleOcrClient(context,p.sourceLanguage);paddle=client;
            try{client.initialize();}catch(Throwable failure){closeOcr();if(JobRuntime.wasStopped(failure))throw new InterruptedException(JobRuntime.STOPPED_MESSAGE);DiagnosticLog.warning(context,"ocr","Optional accurate OCR unavailable; bundled on-device OCR retained.");}
        }
        return ManhwaRenderer.recognize(bitmap,p.sourceLanguage,status::accept,p.accurateOcr,createPaddleSecondaryOcr(paddle),null);
    }
    public void translate(java.util.List<Segment> segments,TranslationProject p) throws Exception {
        if(cancelled)throw new InterruptedException(JobRuntime.STOPPED_MESSAGE);
        if(translator==null){
            ComicTranslationSkill skill=new ComicTranslationSkill(p.comicConfig,ComicSettings.lexicon(context));
            SoraniTranslator next=!p.offlineMode?new SoraniTranslator():new SoraniTranslator("ckb".equals(p.targetLanguage)?new SoraniPackEngine(context):new MlKitOfflineEngine());
            next.setTargetLanguage(p.targetLanguage);next.setComicSkill(skill);translator=next;
        }
        java.util.List<String> source=new ArrayList<>();for(Segment segment:segments)source.add(segment.text);
        ComicTranslationSkill.PageResult result=translator.translatePage(source,p.sourceLanguage,(done,total)->JobRuntime.throwIfStopped());
        JobRuntime.throwIfStopped();ComicTranslationFlow.apply(segments,result,p.sourceLanguage,p.targetLanguage,p.comicConfig);
    }
    public ManhwaRenderer.RenderResult render(Bitmap bitmap,TranslationProject p,TranslationProject.Page page) {
        if(page.layoutPrepared){
            StyledTextRenderer.EditedResult result=StyledTextRenderer.renderEdited(bitmap,page.segments,style->FontCatalog.getTypeface(context,style));
            int rendered=0;for(Segment s:page.segments)if(s.rendered)rendered++;
            return new ManhwaRenderer.RenderResult(result.bitmap,rendered,0,0,0,page.segments);
        }
        return ManhwaRenderer.render(bitmap,page.segments,p.jobStyle,style->FontCatalog.getTypeface(context,style));
    }
    public void closeOcr(){PaddleOcrClient old=paddle;paddle=null;if(old!=null)try{old.close();}catch(Exception ignored){}}
    public void closeTranslator(){SoraniTranslator old=translator;translator=null;if(old!=null)try{old.close();}catch(Exception ignored){}}
    public void cancel(){cancelled=true;SoraniTranslator current=translator;if(current!=null)current.cancelOffline();PaddleOcrClient ocr=paddle;if(ocr!=null)ocr.requestCancel();}
    private ManhwaRenderer.SecondaryOcr createPaddleSecondaryOcr(
            PaddleOcrClient client) {
        if (client == null) {
            return null;
        }
        return (page, boxes) -> {
            List<ManhwaRenderer.LineText> output = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) {
                output.add(new ManhwaRenderer.LineText("", 0f));
            }
            File dir = new File(context.getCacheDir(),
                    "ppocr-lines-" + Long.toString(System.currentTimeMillis()) + "-" + System.nanoTime());
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Could not create PP-OCR crop directory");
            }
            List<File> files = new ArrayList<>();
            List<Integer> boxIndexes = new ArrayList<>();
            try {
                for (int i = 0; i < boxes.size(); i++) {
                    JobRuntime.throwIfStopped();
                    Rect rect = expandedLineRect(boxes.get(i), page);
                    if (rect == null) {
                        continue;
                    }
                    Bitmap crop = Bitmap.createBitmap(page,
                            rect.left, rect.top, rect.width(), rect.height());
                    File originalFile = new File(dir, String.format(
                            Locale.ROOT, "line-%04d-a.png", i));
                    writeBitmapPng(originalFile, crop);
                    files.add(originalFile);
                    boxIndexes.add(i);

                    if (isDarkBitmap(crop)) {
                        Bitmap inverted = invertBitmap(crop);
                        try {
                            File invertedFile = new File(dir, String.format(
                                    Locale.ROOT, "line-%04d-b.png", i));
                            writeBitmapPng(invertedFile, inverted);
                            files.add(invertedFile);
                            boxIndexes.add(i);
                        } finally {
                            inverted.recycle();
                        }
                    }
                }
                if (files.isEmpty()) {
                    return output;
                }
                List<PaddleOcrClient.LineResult> results =
                        client.recognizeFiles(files);
                for (int i = 0; i < Math.min(results.size(), files.size()); i++) {
                    int boxIndex = boxIndexes.get(i);
                    PaddleOcrClient.LineResult result = results.get(i);
                    ManhwaRenderer.LineText current = output.get(boxIndex);
                    if (result.confidence > current.confidence) {
                        output.set(boxIndex, new ManhwaRenderer.LineText(
                                result.text, result.confidence));
                    }
                }
                return output;
            } finally {
                TranslationProjectStore.deleteRecursive(dir);
            }
        };
    }

    private Rect expandedLineRect(Rect box, Bitmap page) {
        if (box == null || page == null) {
            return null;
        }
        int padX = Math.max(5, Math.round(box.width() * 0.08f));
        int padY = Math.max(4, Math.round(box.height() * 0.28f));
        Rect out = new Rect(
                Math.max(0, box.left - padX),
                Math.max(0, box.top - padY),
                Math.min(page.getWidth(), box.right + padX),
                Math.min(page.getHeight(), box.bottom + padY));
        return out.width() >= 8 && out.height() >= 6 ? out : null;
    }

    private void writeBitmapPng(File file, Bitmap bitmap) throws IOException {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("Could not write PP-OCR line crop");
            }
        } finally {
            out.close();
        }
    }

    private boolean isDarkBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int stepX = Math.max(1, width / 12);
        int stepY = Math.max(1, height / 8);
        long sum = 0;
        int count = 0;
        for (int x = 0; x < width; x += stepX) {
            sum += luminance(bitmap.getPixel(x, 0));
            sum += luminance(bitmap.getPixel(x, height - 1));
            count += 2;
        }
        for (int y = 0; y < height; y += stepY) {
            sum += luminance(bitmap.getPixel(0, y));
            sum += luminance(bitmap.getPixel(width - 1, y));
            count += 2;
        }
        return count > 0 && (sum / (double) count) < 118.0;
    }

    private Bitmap invertBitmap(Bitmap source) {
        Bitmap output = Bitmap.createBitmap(
                source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f})));
        canvas.drawBitmap(source, 0f, 0f, paint);
        return output;
    }

    private int luminance(int color) {
        return Math.round(0.299f * Color.red(color)
                + 0.587f * Color.green(color)
                + 0.114f * Color.blue(color));
    }

}

package com.kurdman.app;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;

/** The common automatic pre-render pass for images, image batches and CBZ pages. */
final class ComicTranslationFlow {
    private ComicTranslationFlow() { }

    static int apply(List<Segment> segments, ComicTranslationSkill.PageResult page,
                     String source, String target, ComicTranslationSkill.Config config) throws Exception {
        if (page.results.size() != segments.size()) throw new IOException("Comic bubble alignment mismatch");
        // Validate the whole response before touching geometry or any target.
        for (int i = 0; i < segments.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            ComicTranslationSkill.Result result = page.results.get(i);
            if (result == null || result.text == null || (!ComicTranslationSkill.clean(segments.get(i).text).isEmpty()
                    && ComicTranslationSkill.clean(result.text).isEmpty())) throw new IOException("Empty comic translation response");
        }
        ComicQualityChecks checks = new ComicQualityChecks(config);
        int hints = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            ComicTranslationSkill.Result result = page.results.get(i);
            segment.ckb = result.text;
            segment.originalCkb = result.text;
            segment.translationMethod = result.method;
            segment.translationWarnings = result.warnings;
            if (config.enabled) {
                segment.translationWarnings = ComicQualityChecks.merge(segment.translationWarnings,
                        checks.review(segment.text, result.text, source, target),
                        segment.ocrConfidence >= 0 && segment.ocrConfidence < 0.78f ? "ocr_low" : "");
            }
            segment.readerReview = ComicQualityReview.UNREVIEWED;
            segment.readerReviewBinding = "";
            if (!segment.translationWarnings.isEmpty()) hints++;
        }
        return hints;
    }

    static void bindSummary(Context context, View root, TranslationProject project, boolean busy) {
        TextView label = root.findViewById(R.id.autoSkillResultSummary);
        View optionalReview = root.findViewById(R.id.resultQualityButton);
        Summary summary = busy ? new Summary() : summarize(project);
        label.setVisibility(summary.processed > 0 ? View.VISIBLE : View.GONE);
        optionalReview.setVisibility(summary.hints > 0 && summary.processed > 0 ? View.VISIBLE : View.GONE);
        if (summary.processed > 0) label.setText(context.getString(R.string.auto_result_summary,
                summary.processed, summary.hints));
    }

    static final class Summary {
        int processed, hints, recovered;
    }

    /** Cheap saved-result counters, not a semantic score or a new reader review. */
    static Summary summarize(TranslationProject project) {
        Summary summary = new Summary();
        if (project == null || !project.completed || !project.comicConfig.enabled) return summary;
        for (TranslationProject.Page page : project.pages) for (Segment segment : page.segments) {
            if ((segment.style != null && !segment.style.visible) || ComicTranslationSkill.clean(segment.text).isEmpty()) continue;
            String method = segment.translationMethod == null ? "" : segment.translationMethod;
            if (method.startsWith("comic_")) summary.processed++;
            if ("comic_context_recovered".equals(method)) summary.recovered++;
            if (segment.translationWarnings != null && !segment.translationWarnings.isEmpty()) summary.hints++;
        }
        return summary;
    }
}

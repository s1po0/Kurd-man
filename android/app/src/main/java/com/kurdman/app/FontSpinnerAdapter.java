package com.kurdman.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

/** Spinner rows are rendered in the actual font they represent. */
public class FontSpinnerAdapter extends ArrayAdapter<String> {
    private final List<FontCatalog.FontOption> options;

    public FontSpinnerAdapter(Context context, List<FontCatalog.FontOption> options) {
        super(context, R.layout.spinner_item, FontCatalog.labels(options));
        this.options = options;
        setDropDownViewResource(R.layout.spinner_dropdown_item);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        return buildView(position, convertView, parent, R.layout.spinner_item);
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return buildView(position, convertView, parent,
                R.layout.spinner_dropdown_item);
    }

    private View buildView(int position, View convertView, ViewGroup parent,
                           int layoutResource) {
        TextView textView;
        if (convertView instanceof TextView) {
            textView = (TextView) convertView;
        } else {
            textView = (TextView) LayoutInflater.from(getContext())
                    .inflate(layoutResource, parent, false);
        }
        if (position >= 0 && position < options.size()) {
            FontCatalog.FontOption option = options.get(position);
            textView.setText(option.label);
            textView.setTypeface(FontCatalog.getTypeface(
                    getContext(), option.id, 700, false));
        }
        return textView;
    }
}

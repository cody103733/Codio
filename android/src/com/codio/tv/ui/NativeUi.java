package com.codio.tv.ui;

import android.view.View;

/** The native screens MainActivity drives: TvView (Android TV, remote) or PhoneUi (phones, touch). */
public interface NativeUi {
    View view();

    void showHome();

    void back();

    void dataChanged(boolean addonsChanged);

    void navigate(int item);

    void openDetail(Store.Meta m);

    boolean showingDetail(String id);

    void openSources(Store.Meta m, String videoId);

    void showLoading(Store.Meta m, String videoId);

    void loadingStatus(String kind, String text);

    void hideLoading();

    void showPlayer(Store.Meta m, String videoId);

    void hidePlayer();

    void playerEvent(String signal, Object arg);

    void resumed();

    void trimImages();

    void coreEvent(String name, String message);
}

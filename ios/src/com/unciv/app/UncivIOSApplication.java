package com.unciv.app;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.backends.iosrobovm.IOSAudio;
import com.badlogic.gdx.backends.iosrobovm.IOSGraphics;
import com.badlogic.gdx.backends.iosrobovm.IOSUIViewController;
import com.badlogic.gdx.backends.iosrobovm.IOSScreenBounds;
import org.robovm.apple.coregraphics.CGRect;
import org.robovm.apple.uikit.UIDevice;
import org.robovm.apple.uikit.UIUserInterfaceIdiom;
import org.robovm.apple.uikit.UIViewController;

public class UncivIOSApplication extends IOSApplication {
    private final IOSRuntimeFeatures runtimeFeatures;
    private final IOSGame inviteGame;
    private IOSScreenBounds contentBounds;
    private UIViewController contentController;

    public UncivIOSApplication(ApplicationListener listener, IOSApplicationConfiguration configuration) {
        this(listener, configuration, DefaultIOSRuntimeFeatures.INSTANCE);
    }

    public UncivIOSApplication(ApplicationListener listener, IOSApplicationConfiguration configuration,
                               IOSRuntimeFeatures runtimeFeatures) {
        this(listener, configuration, runtimeFeatures, listener instanceof IOSGame ? (IOSGame) listener : null);
    }

    public UncivIOSApplication(ApplicationListener listener, IOSApplicationConfiguration configuration,
                               IOSRuntimeFeatures runtimeFeatures, IOSGame inviteGame) {
        super(listener, configuration);
        this.runtimeFeatures = runtimeFeatures;
        this.inviteGame = inviteGame;
    }

    @Override protected void handleOpenURL(String url) {
        if (inviteGame != null) postRunnable(() -> inviteGame.openGameInvite(url));
    }

    @Override protected IOSAudio createAudio(IOSApplicationConfiguration configuration) {
        if (!configuration.useAudio) return super.createAudio(configuration);
        IOSAudio audio = runtimeFeatures.createAudio(configuration);
        return audio != null ? audio : super.createAudio(configuration);
    }

    @Override protected IOSUIViewController createUIViewController(IOSGraphics graphics) {
        UncivIOSViewController controller = new UncivIOSViewController(this, graphics);
        contentController = controller;
        return controller;
    }

    @Override protected IOSScreenBounds computeBounds() {
        IOSScreenBounds fallback = super.computeBounds();
        // UIKit touch locations are relative to the graphics view. In a resizable iPad window,
        // window/status-bar geometry can differ from that view; graphics and input must agree.
        UIViewController controller = contentController;
        if (UIDevice.getCurrentDevice().getUserInterfaceIdiom() != UIUserInterfaceIdiom.Pad
                || controller == null || !controller.isViewLoaded()) {
            contentBounds = fallback;
            return fallback;
        }
        CGRect view = controller.getView().getBounds();
        int width = (int)Math.round(view.getWidth());
        int height = (int)Math.round(view.getHeight());
        if (width <= 0 || height <= 0) return fallback;
        double scale = fallback.width > 0 ? (double)fallback.backBufferWidth / fallback.width : 1;
        contentBounds = new IOSScreenBounds(0, 0, width, height,
                (int)Math.round(width * scale), (int)Math.round(height * scale));
        return contentBounds;
    }

    @Override public IOSScreenBounds getScreenBounds() {
        return contentBounds == null ? super.getScreenBounds() : contentBounds;
    }
}

package com.unciv.app;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.iosrobovm.IOSApplication;
import com.badlogic.gdx.backends.iosrobovm.IOSApplicationConfiguration;
import com.badlogic.gdx.backends.iosrobovm.IOSAudio;
import com.badlogic.gdx.backends.iosrobovm.IOSGraphics;
import com.badlogic.gdx.backends.iosrobovm.IOSUIViewController;

public class UncivIOSApplication extends IOSApplication {
    private final IOSRuntimeFeatures runtimeFeatures;
    private final IOSGame inviteGame;

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
        return new UncivIOSViewController(this, graphics);
    }
}

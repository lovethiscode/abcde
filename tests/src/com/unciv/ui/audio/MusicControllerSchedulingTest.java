package com.unciv.ui.audio;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.unciv.UncivGame;
import com.unciv.logic.files.UncivFiles;
import com.unciv.models.metadata.GameSettings;
import com.unciv.utils.PlatformCapabilities;
import kotlin.Unit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MusicControllerSchedulingTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();
    private Application previousApp;
    private Audio previousAudio;
    private UncivGame previousGame;
    private ControlledExecutor executor;
    private MusicController controller;
    private Audio audio;
    private final List<FakeMusic> created = new CopyOnWriteArrayList<>();
    private final Queue<Runnable> glQueue = new ConcurrentLinkedQueue<>();
    private int glPosts;
    private final Map<String, Object> oldStatics = new HashMap<>();

    @Before public void setUp() throws Exception {
        previousApp = Gdx.app; previousAudio = Gdx.audio;
        previousGame = UncivGame.Companion.isCurrentInitialized() ? UncivGame.Companion.getCurrent() : new UncivGame();
        for (String name : Arrays.asList("needOwnTimer", "ticksPerSecond", "defaultFadingStep")) {
            Field field = MusicController.class.getDeclaredField(name); field.setAccessible(true);
            oldStatics.put(name, field.get(null));
        }
        Application app = mock(Application.class);
        when(app.getType()).thenReturn(Application.ApplicationType.iOS);
        doAnswer(call -> { glPosts++; glQueue.add(call.getArgument(0)); return null; }).when(app).postRunnable(any(Runnable.class));
        Gdx.app = app;
        UncivFiles files = mock(UncivFiles.class);
        when(files.getLocalFile(anyString())).thenAnswer(call -> new FileHandle(new java.io.File(directory.getRoot(), call.getArgument(0))));
        GameSettings settings = new GameSettings();
        settings.setPauseBetweenTracks(10);
        PlatformCapabilities capabilities = mock(PlatformCapabilities.class);
        when(capabilities.getOggAudio()).thenReturn(true);
        UncivGame game = mock(UncivGame.class);
        when(game.getFiles()).thenReturn(files);
        when(game.getSettings()).thenReturn(settings);
        when(game.getPlatformCapabilities()).thenReturn(capabilities);
        UncivGame.Companion.setCurrent(game);
        file("first-Ambient"); file("second-Ambient");
        audio = mock(Audio.class);
        when(audio.newMusic(any(FileHandle.class))).thenAnswer(call -> {
            FileHandle file = call.getArgument(0);
            if (file.name().startsWith("broken")) throw new GdxRuntimeException("injected load failure");
            FakeMusic music = new FakeMusic(file.name());
            music.failPlay = file.name().startsWith("unplayable");
            created.add(music);
            return music;
        });
        Gdx.audio = audio;
        executor = new ControlledExecutor();
        controller = new MusicController(executor, () -> executor.now);
    }

    @After public void tearDown() throws Exception {
        try {
            if (controller != null) { controller.gracefulShutdown(); executor.advance(2000); }
            if (executor != null) { executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        } finally {
            for (Map.Entry<String, Object> entry : oldStatics.entrySet()) {
                Field field = MusicController.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(null, entry.getValue());
            }
            UncivGame.Companion.setCurrent(previousGame);
            Gdx.app = previousApp; Gdx.audio = previousAudio;
        }
    }

    private FileHandle file(String name) {
        FileHandle file = new FileHandle(new java.io.File(directory.getRoot(), "music/" + name + ".mp3"));
        file.parent().mkdirs(); file.writeString("fixture", false);
        return file;
    }

    private FakeMusic start(String name) throws Exception {
        assertTrue(controller.startTrack(new MusicController.MusicTrackInfo("", name, "mp3")));
        executor.advance(50);
        return created.get(created.size() - 1);
    }

    private void drainGl() { Runnable next; while ((next = glQueue.poll()) != null) next.run(); }

    @Test public void audB01NoWorkOrFullyPausedHasNoTimedCallbacks() throws Exception {
        executor.advance(60000);
        assertEquals(0, executor.runs);
        FakeMusic music = start("first-Ambient");
        controller.pause(1f, false); executor.advance(1000);
        assertFalse(music.playing);
        assertEquals(0, executor.activeCount());
        int runs = executor.runs, checks = music.checks;
        executor.advance(60000);
        assertEquals(runs, executor.runs);
        assertEquals(checks, music.checks);
    }

    @Test public void audB02StablePlaybackUsesOneSecondChecksWithoutGlPolling() throws Exception {
        controller.onChange(info -> Unit.INSTANCE);
        start("first-Ambient"); drainGl();
        int before = executor.positiveRuns, posts = glPosts;
        executor.advance(60000);
        assertEquals(60, executor.positiveRuns - before);
        assertEquals(posts, glPosts);
        assertEquals(1, executor.activeCount());
    }

    @Test public void audB03CompletionIsImmediateAndOldEventsCannotClearReplacement() throws Exception {
        FakeMusic first = start("first-Ambient");
        Runnable oldCompletion = first.finishEvent();
        oldCompletion.run(); executor.advance(0);
        assertEquals(1, first.disposals);
        oldCompletion.run(); executor.advance(0);
        assertEquals(1, first.disposals);
        FakeMusic second = start("second-Ambient");
        oldCompletion.run(); executor.advance(0);
        assertTrue(second.playing);
        assertEquals(0, second.disposals);

        Music.OnCompletionListener priorPlayback = second.listener;
        controller.pause(1f, false); executor.advance(1000);
        controller.resume(1f); executor.advance(50);
        priorPlayback.onCompletion(second); executor.advance(0);
        assertTrue(second.playing);
        assertEquals(0, second.disposals);
    }

    @Test public void audB04WatchdogHandlesMissingCompletionWithinOneSecond() throws Exception {
        FakeMusic music = start("first-Ambient");
        music.playing = false;
        executor.advance(999); assertEquals(0, music.disposals);
        executor.advance(1); assertEquals(1, music.disposals);
    }

    @Test public void audB05FadeTimingAndReversalAreNotAcceleratedByVolumeEvents() throws Exception {
        controller.playOverlay(file("overlay"), 1f, true, true);
        FakeMusic overlay = created.get(0);
        executor.advance(450);
        assertEquals(0.5f, overlay.volume, 0.06f);
        for (int i = 0; i < 20; i++) controller.setOverlayVolume(1f);
        assertEquals(0.5f, overlay.volume, 0.06f);
        controller.pause(1f, false); executor.advance(450);
        assertFalse(overlay.playing);
        controller.resume(1f); executor.advance(900);
        assertTrue(overlay.playing); assertEquals(1f, overlay.volume, 0.001f);

        FakeMusic old = start("first-Ambient");
        assertTrue(controller.chooseTrack("second", "", EnumSet.of(MusicTrackChooserFlags.PrefixMustMatch, MusicTrackChooserFlags.SlowFade)));
        executor.advance(2250);
        assertEquals(0.15f, old.volume, 0.02f);
        executor.advance(2300); assertEquals(1, old.disposals);
    }

    @Test public void audB06SilenceDeadlinesAndDurationEditsUseElapsedMonotonicTime() throws Exception {
        for (int seconds : new int[]{0, 10, 120}) {
            controller.setVolume(0); controller.setVolume(0.5f); controller.setSilenceLength(seconds);
            FakeMusic music = start("first-Ambient");
            int count = created.size(); music.finishEvent().run(); executor.advance(0);
            if (seconds > 0) {
                executor.advance(seconds * 1000L - 1); assertEquals(count, created.size());
                executor.advance(1);
            }
            assertEquals(count + 1, created.size());
        }
        controller.setVolume(0); controller.setVolume(0.5f); controller.setSilenceLength(10);
        FakeMusic music = start("first-Ambient"); music.finishEvent().run(); executor.advance(5000);
        int count = created.size(); controller.setSilenceLength(6);
        executor.advance(999); assertEquals(count, created.size());
        executor.advance(1); assertEquals(count + 1, created.size());
    }

    @Test public void audB07LoadAndPlayFailuresKeepTheirRetryDelayWithoutBusyLoop() throws Exception {
        controller.setSilenceLength(1);
        FileHandle broken = file("broken-Ambient");
        assertTrue(controller.startTrack(new MusicController.MusicTrackInfo("", "broken-Ambient", "mp3")));
        broken.delete();
        executor.advance(999); assertEquals(0, created.size());
        executor.advance(1); assertEquals(1, created.size());
        controller.setVolume(0); controller.setVolume(0.5f);
        FileHandle unplayable = file("unplayable-Ambient");
        assertTrue(controller.startTrack(new MusicController.MusicTrackInfo("", "unplayable-Ambient", "mp3")));
        unplayable.delete();
        int count = created.size();
        executor.advance(52049); assertEquals(count, created.size());
        executor.advance(1); assertEquals(count + 1, created.size());
    }

    @Test public void audB08MainNextAndOverlayAllFinishPausingBeforeTimerStops() throws Exception {
        FakeMusic first = start("first-Ambient");
        FakeMusic second = start("second-Ambient");
        controller.playOverlay(file("overlay"), 0.5f, true, false);
        FakeMusic overlay = created.get(created.size() - 1);
        executor.advance(50);
        controller.pause(1f, false); executor.advance(1000);
        assertFalse(first.playing); assertFalse(second.playing); assertFalse(overlay.playing);
        assertEquals(1, second.disposals);
        assertEquals(0, executor.activeCount());
        controller.resume(1f); executor.advance(1000);
        assertTrue(first.playing); assertTrue(overlay.playing);
        assertEquals(1, executor.activeCount());
    }

    @Test public void audB09OverlayAndVoiceWorkWhenMainMusicIsDisabled() throws Exception {
        controller.setVolume(0);
        controller.playOverlay(file("city"), 0.5f, true, true);
        FakeMusic city = created.get(0); executor.advance(1000);
        assertTrue(city.playing);
        controller.stopOverlay(); executor.advance(1000);
        assertEquals(1, city.disposals); assertEquals(0, executor.activeCount());
        controller.playOverlay(file("voice"), 1f, false, false);
        FakeMusic voice = created.get(1); executor.advance(50);
        voice.finishEvent().run(); executor.advance(0);
        assertEquals(1, voice.disposals); assertEquals(0, executor.activeCount());
    }

    @Test public void audB10LateLoadAfterPauseIsDisposedAndCannotResurrectPlayback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        file("late");
        FakeMusic late = new FakeMusic("late.mp3");
        when(audio.newMusic(argThat(f -> f != null && f.name().equals("late.mp3")))).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return late;
        });
        ExecutorService loader = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = loader.submit(() -> controller.startTrack(new MusicController.MusicTrackInfo("", "late", "mp3")));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            controller.pause(1f, false);
            release.countDown(); result.get(5, TimeUnit.SECONDS);
            executor.advance(1000);
            assertEquals(0, late.plays); assertEquals(1, late.disposals);
            assertEquals(0, executor.activeCount());
        } finally { release.countDown(); loader.shutdownNow(); }
    }

    @Test public void audB11LifecycleResumesSystemPauseButPreservesUserPause() throws Exception {
        FakeMusic music = start("first-Ambient");
        controller.pause(1f, true); executor.advance(60000);
        assertFalse(music.playing); assertEquals(0, executor.activeCount());
        controller.resumeFromShutdown(); executor.advance(1000);
        assertTrue(music.playing);
        controller.pause(1f, false); executor.advance(1000);
        int plays = music.plays;
        controller.pause(1f, true); controller.resumeFromShutdown(); executor.advance(1000);
        assertEquals(plays, music.plays); assertFalse(music.playing);

        controller.playOverlay(file("city"), 0.5f, true, false);
        FakeMusic city = created.get(created.size() - 1); executor.advance(50);
        controller.pause(1f, true); controller.resumeFromShutdown(); executor.advance(1000);
        assertTrue(city.playing); assertFalse(music.playing);
    }

    @Test public void audB12DesktopAudioCallbackKeepsLegacyBehavior() {
        MusicController legacy = new MusicController(null, () -> 0L);
        kotlin.jvm.functions.Function0<Unit> tick = legacy.getAudioLoopCallback();
        assertTrue(legacy.startTrack(new MusicController.MusicTrackInfo("", "first-Ambient", "mp3")));
        FakeMusic music = created.get(0);
        tick.invoke(); tick.invoke();
        assertTrue(music.playing); assertNull(music.listener);
        legacy.pause(1f, false);
        for (int i = 0; i < 60; i++) tick.invoke();
        assertFalse(music.playing);
        legacy.resume(1f); assertTrue(music.playing);
        legacy.gracefulShutdown();
        for (int i = 0; i < 65; i++) tick.invoke();
        assertEquals(1, music.disposals); assertEquals(0, executor.activeCount());
    }

    @Test public void audB13MuteIsReusableButLifecycleDisposalClosesTheWorker() throws Exception {
        start("first-Ambient");
        controller.setVolume(0); assertEquals(0, executor.activeCount());
        controller.setVolume(0.5f);
        FakeMusic next = start("second-Ambient"); assertTrue(next.playing);
        controller.pause(1f, true);
        controller.gracefulShutdown();
        assertTrue(executor.isShutdown());
        assertFalse(controller.startTrack(new MusicController.MusicTrackInfo("", "first-Ambient", "mp3")));
        assertEquals(1, next.disposals); assertEquals(0, executor.activeCount());
    }

    @Test public void terminalShutdownDisposesLateLoadsAndClosesEvenWhenDisposeFails() throws Exception {
        FakeMusic first = start("first-Ambient");
        controller.playOverlay(file("city"), 0.5f, true, false);
        FakeMusic city = created.get(created.size() - 1);
        executor.advance(50);
        first.failDispose = true;
        controller.pause(1f, true);
        controller.gracefulShutdown();
        assertTrue(executor.isShutdown());
        assertEquals(1, first.disposals); assertEquals(1, city.disposals);
        assertEquals(0, executor.activeCount());

        executor = new ControlledExecutor();
        controller = new MusicController(executor, () -> executor.now);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeMusic late = new FakeMusic("late.mp3"); file("late");
        when(audio.newMusic(argThat(f -> f != null && f.name().equals("late.mp3")))).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return late;
        });
        ExecutorService loader = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = loader.submit(() -> controller.startTrack(new MusicController.MusicTrackInfo("", "late", "mp3")));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            controller.gracefulShutdown();
            release.countDown(); result.get(5, TimeUnit.SECONDS);
            assertTrue(executor.isShutdown());
            assertEquals(0, late.plays); assertEquals(1, late.disposals);
            assertEquals(0, executor.activeCount());
        } finally { release.countDown(); loader.shutdownNow(); }
    }

    @Test public void completionDuringCrossfadePreservesTheOtherSlot() throws Exception {
        FakeMusic first = start("first-Ambient");
        FakeMusic second = start("second-Ambient");
        second.finishEvent().run(); executor.advance(0);
        assertEquals(1, second.disposals); assertEquals(0, first.disposals);
        controller.setVolume(0); controller.setVolume(0.5f);
        first = start("first-Ambient"); second = start("second-Ambient");
        first.finishEvent().run(); executor.advance(0);
        assertEquals(1, first.disposals); assertEquals(0, second.disposals);
        assertTrue(controller.isPlaying());
        executor.advance(1000); assertTrue(second.playing);
    }

    @Test public void fadeSpeedFactorsKeepTheExpectedStepTiming() throws Exception {
        for (float speed : new float[]{0.5f, 2f, 1000f}) {
            controller.setVolume(0); controller.setVolume(0.5f);
            FakeMusic music = start("first-Ambient");
            controller.pause(speed, false);
            long finish = speed == 0.5f ? 1800 : speed == 2f ? 450 : 50;
            executor.advance(finish - 50); assertTrue(music.playing);
            executor.advance(50); assertFalse(music.playing);
            assertEquals(0, executor.activeCount());
            controller.resume(speed); executor.advance(finish + 50);
            assertTrue(music.playing); assertEquals(0.3f, music.volume, 0.001f);
        }
    }

    @Test public void nonIosOwnTimerStillFadesAndTerminates() throws Exception {
        // Other audio-loop tests can leave the process-wide desktop callback mode enabled.
        Field ownTimer = MusicController.class.getDeclaredField("needOwnTimer");
        ownTimer.setAccessible(true); ownTimer.set(null, true);
        MusicController legacy = new MusicController(null, System::nanoTime);
        try {
            assertTrue(legacy.startTrack(new MusicController.MusicTrackInfo("", "first-Ambient", "mp3")));
            FakeMusic music = created.get(0);
            // startTrack queues the slot; wait until the timer has actually started fading it.
            assertTrue(music.fadeSignal.await(5, TimeUnit.SECONDS));
            legacy.pause(1000f, false);
            assertTrue(music.pauseSignal.await(5, TimeUnit.SECONDS));
            assertFalse(music.playing);
            legacy.resume(1000f); assertTrue(music.playing);
            legacy.gracefulShutdown();
            assertTrue(music.disposeSignal.await(5, TimeUnit.SECONDS));
        } finally { legacy.setVolume(0); }
    }

    static final class FakeMusic implements Music {
        final String name;
        boolean playing, looping, failPlay, failDispose;
        int plays, pauses, disposals, checks;
        float volume, position;
        OnCompletionListener listener;
        final CountDownLatch pauseSignal = new CountDownLatch(1), disposeSignal = new CountDownLatch(1);
        final CountDownLatch fadeSignal = new CountDownLatch(1);
        FakeMusic(String name) { this.name = name; }
        Runnable finishEvent() { playing = false; OnCompletionListener captured = listener; return () -> captured.onCompletion(this); }
        @Override public void play() { if (failPlay) throw new GdxRuntimeException("injected play failure"); plays++; playing = true; }
        @Override public void pause() { pauses++; playing = false; pauseSignal.countDown(); }
        @Override public void stop() { playing = false; position = 0; }
        @Override public boolean isPlaying() { checks++; return playing; }
        @Override public void setLooping(boolean value) { looping = value; }
        @Override public boolean isLooping() { return looping; }
        @Override public void setVolume(float value) { volume = value; if (playing && value > 0) fadeSignal.countDown(); }
        @Override public float getVolume() { return volume; }
        @Override public void setPan(float pan, float value) { volume = value; }
        @Override public void setPosition(float value) { position = value; }
        @Override public float getPosition() { return position; }
        @Override public void dispose() { disposals++; playing = false; disposeSignal.countDown(); if (failDispose) throw new GdxRuntimeException("injected disposal failure"); }
        @Override public void setOnCompletionListener(OnCompletionListener value) { listener = value; }
    }

    static final class ControlledExecutor extends ScheduledThreadPoolExecutor {
        final List<TimedTask> tasks = new CopyOnWriteArrayList<>();
        long now;
        int runs, positiveRuns;
        ControlledExecutor() { super(1, runnable -> { Thread t = new Thread(runnable, "Music test owner"); t.setDaemon(true); return t; }); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            if (isShutdown()) throw new RejectedExecutionException();
            TimedTask task = new TimedTask(command, now + unit.toNanos(delay), delay > 0);
            tasks.add(task); return task;
        }
        int activeCount() { return (int)tasks.stream().filter(t -> !t.isDone()).count(); }
        void advance(long millis) throws Exception {
            long target = now + TimeUnit.MILLISECONDS.toNanos(millis);
            while (true) {
                TimedTask task = tasks.stream().filter(t -> !t.isDone() && t.at <= target)
                    .min(Comparator.comparingLong(t -> t.at)).orElse(null);
                if (task == null) break;
                now = task.at;
                super.schedule((Callable<Void>) () -> {
                    runs++; if (task.positive) positiveRuns++;
                    task.run(); task.get(); return null;
                }, 0, TimeUnit.NANOSECONDS).get(5, TimeUnit.SECONDS);
            }
            now = target;
        }
    }

    static final class TimedTask extends FutureTask<Void> implements ScheduledFuture<Void> {
        final long at;
        final boolean positive;
        TimedTask(Runnable command, long at, boolean positive) { super(command, null); this.at = at; this.positive = positive; }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(at, TimeUnit.NANOSECONDS); }
        @Override public int compareTo(Delayed other) { return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS)); }
    }
}

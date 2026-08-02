package com.marcnuri.mnimapsync;

import com.sun.mail.imap.IdleManager;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.event.MessageChangedEvent;
import jakarta.mail.event.MessageChangedListener;
import jakarta.mail.event.MessageCountAdapter;
import jakarta.mail.event.MessageCountEvent;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.marcnuri.mnimapsync.imap.IMAPUtils.openStore;

/**
 * Keeps source IMAP folders in IDLE mode and serializes syncs triggered by server events.
 */
final class ImapIdleWatcher {

    private static final Logger LOGGER = Logger.getLogger(ImapIdleWatcher.class.getName());

    private final SyncOptions syncOptions;
    private final AtomicBoolean running;
    private final AtomicBoolean pendingSync;
    private final AtomicBoolean syncWorkerRunning;
    private final AtomicBoolean refreshWatches;
    private final ExecutorService syncExecutor;
    private final ExecutorService idleExecutor;
    private final ScheduledExecutorService fallbackExecutor;
    private final List<IMAPFolder> idleFolders;
    private volatile IMAPStore idleStore;
    private volatile IdleManager idleManager;

    ImapIdleWatcher(SyncOptions syncOptions) {
        this.syncOptions = syncOptions;
        running = new AtomicBoolean(true);
        pendingSync = new AtomicBoolean(false);
        syncWorkerRunning = new AtomicBoolean(false);
        refreshWatches = new AtomicBoolean(false);
        syncExecutor = Executors.newSingleThreadExecutor();
        idleExecutor = Executors.newSingleThreadExecutor();
        fallbackExecutor = Executors.newSingleThreadScheduledExecutor();
        idleFolders = new CopyOnWriteArrayList<>();
    }

    void watch() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "mnimapsync-idle-shutdown"));
        fallbackExecutor.scheduleWithFixedDelay(this::refreshWatchesAndSync,
            syncOptions.getWatchInterval(), syncOptions.getWatchInterval(), TimeUnit.SECONDS);

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            watchFolders();
            waitBeforeReconnect();
        }
        stop();
    }

    private void watchFolders() {
        final MessageCountAdapter messageCountListener = new MessageCountAdapter() {
            @Override
            public void messagesAdded(MessageCountEvent event) {
                folderChanged((Folder) event.getSource());
            }

            @Override
            public void messagesRemoved(MessageCountEvent event) {
                folderChanged((Folder) event.getSource());
            }
        };
        final MessageChangedListener messageChangedListener =
            event -> folderChanged(event.getMessage().getFolder());
        try (IMAPStore store = openStore(syncOptions.getSourceHost(), syncOptions.getThreads(), true)) {
            idleStore = store;
            idleManager = new IdleManager(Session.getInstance(new Properties()), idleExecutor);
            for (String folderName : getWatchedFolderNames(store)) {
                watchFolder(store, folderName, messageCountListener, messageChangedListener);
            }
            requestSync();
            while (running.get() && store.isConnected() && allFoldersOpen() && !refreshWatches.get()) {
                Thread.sleep(500L);
            }
        } catch (AuthenticationFailedException exception) {
            LOGGER.log(Level.SEVERE, "IMAP IDLE authentication failed; stopping watcher", exception);
            stop();
        } catch (MessagingException | GeneralSecurityException | IOException exception) {
            if (running.get()) {
                LOGGER.log(Level.WARNING, "IMAP IDLE connection interrupted; reconnecting", exception);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            refreshWatches.set(false);
            closeIdleFolders(messageCountListener, messageChangedListener);
            stopIdleManager();
            idleStore = null;
        }
    }

    private List<String> getWatchedFolderNames(IMAPStore store) throws MessagingException {
        if (syncOptions.getWatchFolder() != null) {
            final List<String> configuredFolder = new ArrayList<>();
            configuredFolder.add(syncOptions.getWatchFolder());
            return configuredFolder;
        }
        final Set<String> folderNames = new LinkedHashSet<>();
        collectMessageFolders(store.getDefaultFolder(), folderNames);
        return new ArrayList<>(folderNames);
    }

    private void collectMessageFolders(Folder parent, Set<String> folderNames) throws MessagingException {
        for (Folder folder : parent.list()) {
            if ((folder.getType() & Folder.HOLDS_MESSAGES) == Folder.HOLDS_MESSAGES) {
                folderNames.add(folder.getFullName());
            }
            if ((folder.getType() & Folder.HOLDS_FOLDERS) == Folder.HOLDS_FOLDERS) {
                collectMessageFolders(folder, folderNames);
            }
        }
    }

    private void watchFolder(IMAPStore store, String folderName, MessageCountAdapter messageCountListener,
        MessageChangedListener messageChangedListener) {

        try {
            final Folder folder = store.getFolder(folderName);
            if (!(folder instanceof IMAPFolder)) {
                LOGGER.log(Level.WARNING, "Folder does not support IMAP IDLE: {0}", folderName);
                return;
            }
            final IMAPFolder imapFolder = (IMAPFolder) folder;
            imapFolder.open(Folder.READ_ONLY);
            imapFolder.addMessageCountListener(messageCountListener);
            imapFolder.addMessageChangedListener(messageChangedListener);
            idleManager.watch(imapFolder);
            idleFolders.add(imapFolder);
        } catch (MessagingException exception) {
            LOGGER.log(Level.WARNING, "Unable to watch IMAP folder: " + folderName, exception);
        }
    }

    private void folderChanged(Folder folder) {
        requestSync();
        final IdleManager manager = idleManager;
        if (manager != null && running.get() && folder.isOpen()) {
            try {
                manager.watch(folder);
            } catch (MessagingException exception) {
                LOGGER.log(Level.WARNING, "Unable to resume IMAP IDLE for folder: "
                    + folder.getFullName(), exception);
            }
        }
    }

    private boolean allFoldersOpen() {
        if (idleFolders.isEmpty()) {
            return false;
        }
        for (IMAPFolder folder : idleFolders) {
            if (!folder.isOpen()) {
                return false;
            }
        }
        return true;
    }

    private void refreshWatchesAndSync() {
        refreshWatches.set(true);
        requestSync();
    }

    private void requestSync() {
        pendingSync.set(true);
        if (syncWorkerRunning.compareAndSet(false, true)) {
            syncExecutor.execute(this::runPendingSyncs);
        }
    }

    private void runPendingSyncs() {
        try {
            do {
                pendingSync.set(false);
                MNIMAPSync.synchronizeAndReport(syncOptions);
            } while (running.get() && pendingSync.get());
        } finally {
            syncWorkerRunning.set(false);
            if (running.get() && pendingSync.get()) {
                requestSync();
            }
        }
    }

    private void waitBeforeReconnect() {
        if (!running.get()) {
            return;
        }
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        fallbackExecutor.shutdownNow();
        stopIdleManager();
        closeIdleFolders(null, null);
        final IMAPStore store = idleStore;
        if (store != null && store.isConnected()) {
            try {
                store.close();
            } catch (MessagingException exception) {
                LOGGER.log(Level.FINE, "Unable to close IMAP IDLE store", exception);
            }
        }
        idleExecutor.shutdownNow();
        syncExecutor.shutdownNow();
    }

    private void stopIdleManager() {
        final IdleManager manager = idleManager;
        idleManager = null;
        if (manager != null) {
            manager.stop();
        }
    }

    private void closeIdleFolders(MessageCountAdapter messageCountListener,
        MessageChangedListener messageChangedListener) {

        for (IMAPFolder folder : idleFolders) {
            if (messageCountListener != null) {
                folder.removeMessageCountListener(messageCountListener);
            }
            if (messageChangedListener != null) {
                folder.removeMessageChangedListener(messageChangedListener);
            }
            if (folder.isOpen()) {
                try {
                    folder.close(false);
                } catch (MessagingException exception) {
                    LOGGER.log(Level.FINE, "Unable to close IMAP IDLE folder", exception);
                }
            }
        }
        idleFolders.clear();
    }
}

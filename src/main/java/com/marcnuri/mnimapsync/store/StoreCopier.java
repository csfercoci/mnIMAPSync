/*
 * Copyright 2013 Marc Nuri San Felix
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.marcnuri.mnimapsync.store;

import com.marcnuri.mnimapsync.MNIMAPSync;
import com.marcnuri.mnimapsync.index.Index;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.ReadOnlyFolderException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.marcnuri.mnimapsync.imap.IMAPUtils.sourceFolderNameToTarget;

/**
 *
 * @author Marc Nuri <marc@marcnuri.com>
 */
public final class StoreCopier {

    private final ExecutorService service;
    private final IMAPStore sourceStore;
    private final IMAPStore targetStore;
    private final Index sourceIndex;
    private final Index targetIndex;
    private final AtomicInteger foldersCopiedCount;
    private final AtomicInteger foldersSkippedCount;
    private final AtomicLong messagesCopiedCount;
    private final AtomicLong messagesSkippedCount;
    //If no empty, we shouldn't allow deletion
    private final List<MessagingException> copyExceptions;

    public StoreCopier(IMAPStore sourceStore, Index sourceIndex, IMAPStore targetStore,
            Index targetIndex, int threads) {
        this.sourceStore = sourceStore;
        this.sourceIndex = sourceIndex;
        this.targetStore = targetStore;
        this.targetIndex = targetIndex;
        service = Executors.newFixedThreadPool(threads);
        foldersCopiedCount = new AtomicInteger();
        foldersSkippedCount = new AtomicInteger();
        messagesCopiedCount = new AtomicLong();
        messagesSkippedCount = new AtomicLong();
        this.copyExceptions = Collections.synchronizedList(new ArrayList<>());
    }

    public final void copy() throws InterruptedException {
        try {
            sourceIndex
                .setFolderSeparator(String.valueOf(sourceStore.getDefaultFolder().getSeparator()));
            //Copy Folder Structure
            copySourceFolder(sourceStore.getDefaultFolder());
            //Copy messages
            copySourceMessages((IMAPFolder) sourceStore.getDefaultFolder());
        } catch (MessagingException ex) {
            Logger.getLogger(StoreCopier.class.getName()).log(Level.SEVERE, null, ex);
        }
        service.shutdown();
        service.awaitTermination(1, TimeUnit.DAYS);
    }

    /**
     * Create folders in the target server recursively from the source.
     *
     * It also indexes the source store folders if we want to delete target folders that no longer
     * exist
     */
    private void copySourceFolder(Folder folder) throws MessagingException {
        final String sourceFolderName = folder.getFullName();
         String targetFolderName = sourceFolderNameToTarget(sourceFolderName, sourceIndex,
            targetIndex);
        //Index for delete after copy (if necessary)
        if (sourceIndex != null) {
            sourceIndex.addFolder(sourceFolderName);
        }
        //Copy folder
        Folder targetStoreFolder=targetStore.getFolder(targetFolderName);
        if (!targetIndex.containsFolder(targetFolderName)) {

            if (!targetStoreFolder.exists() && !targetStoreFolder.create(folder.getType())) {
                throw new MessagingException(String.format(
                        "Couldn't create folder: %s in target server with path  %s.\n", sourceFolderName ,targetStoreFolder.getFullName()));
            }else{
                    System.out.println("Folder existed" + targetFolderName + " "+  targetStoreFolder.exists());
            }
            incrementFoldersCopiedCount();
        } else {
            incrementFoldersSkippedCount();
        }
        //Folder recursion. Get all children
        if ((folder.getType() & Folder.HOLDS_FOLDERS) == Folder.HOLDS_FOLDERS) {
            for (Folder child : folder.list()) {
                copySourceFolder(child);
            }
        }
    }

    /**
     * Once the folder structure has been created it copies messages recursively from the root
     * folder.
     */
    private void copySourceMessages(IMAPFolder sourceFolder) throws MessagingException {
        if (sourceFolder != null) {
            final String sourceFolderName = sourceFolder.getFullName();
            final String targetFolderName = sourceFolderNameToTarget(sourceFolderName, sourceIndex,
                targetIndex);
            if ((sourceFolder.getType() & Folder.HOLDS_MESSAGES) == Folder.HOLDS_MESSAGES) {
                //Manage Servers with public/read only folders.
                try {
                    if (!sourceFolder.isOpen()) {
                        sourceFolder.open(Folder.READ_WRITE);
                    }
                } catch (ReadOnlyFolderException ex) {
                    if (sourceFolder.getMode() != Folder.READ_ONLY) {
                        sourceFolder.expunge();
                    }
                }
                ///////////////////////
                final int messageCount = sourceFolder.getMessageCount();
                sourceFolder.close(false);

                int pos = 1;
                while (pos + MNIMAPSync.BATCH_SIZE <= messageCount) {
                    //Copy messages
                    service.execute(new MessageCopier(this, sourceFolderName, targetFolderName, pos,
                            pos + MNIMAPSync.BATCH_SIZE, targetIndex.getFolderMessages(
                                    targetFolderName)));
                    pos = pos + MNIMAPSync.BATCH_SIZE;
                }
                service.execute(new MessageCopier(this, sourceFolderName, targetFolderName, pos,
                        messageCount,
                        targetIndex.getFolderMessages(targetFolderName)));
            }
            //Folder recursion. Get all children
            if ((sourceFolder.getType() & Folder.HOLDS_FOLDERS) == Folder.HOLDS_FOLDERS) {
                for (Folder child : sourceFolder.list()) {
                    //if (child.getName().toUpperCase().contains("INBOX"))
                    copySourceMessages((IMAPFolder) child);
                }
            }
        }
    }

    public final boolean hasCopyException() {
        synchronized (copyExceptions) {
            return !copyExceptions.isEmpty();
        }
    }

    private void incrementFoldersCopiedCount() {
        foldersCopiedCount.getAndAdd(1);
    }

    private void incrementFoldersSkippedCount() {
        foldersSkippedCount.getAndAdd(1);
    }

    protected final void updatedMessagesCopiedCount(long delta) {
        messagesCopiedCount.getAndAdd(delta);
    }

    protected final void updateMessagesSkippedCount(long delta) {
        messagesSkippedCount.getAndAdd(delta);
    }

    public final int getFoldersCopiedCount() {
        return foldersCopiedCount.get();
    }

    public final int getFoldersSkippedCount() {
        return foldersSkippedCount.get();
    }

    public final long getMessagesCopiedCount() {
        return messagesCopiedCount.get();
    }

    public final long getMessagesSkippedCount() {
        return messagesSkippedCount.get();
    }

    final IMAPStore getSourceStore() {
        return sourceStore;
    }

    final Index getSourceIndex() {
        return sourceIndex;
    }

    final IMAPStore getTargetStore() {
        return targetStore;
    }

    public final synchronized List<MessagingException> getCopyExceptions() {
        return copyExceptions;
    }

}

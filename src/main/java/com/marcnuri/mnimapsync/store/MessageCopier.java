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

import com.marcnuri.mnimapsync.index.Index;
import com.marcnuri.mnimapsync.index.MessageId;
import com.marcnuri.mnimapsync.index.MessageState;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import jakarta.mail.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Marc Nuri <marc@marcnuri.com>
 */
public final class MessageCopier implements Runnable {

    private final StoreCopier storeCopier;
    private final String sourceFolderName;
    private final String targetFolderName;
    private final int start;
    private final int end;
    private final Set<MessageId> targetFolderMessages;

    public MessageCopier(StoreCopier storeCopier, String sourceFolderName, String targetFolderName,
            int start, int end, Set<MessageId> targetFolderMessages) {
        this.storeCopier = storeCopier;
        this.sourceFolderName = sourceFolderName;
        this.targetFolderName = targetFolderName;
        this.start = start;
        this.end = end;
        this.targetFolderMessages = targetFolderMessages;
    }

    public void run() {
        final int updateCount = 20;
        long copied = 0L, skipped = 0L;
        boolean expungeTarget = false;
        Folder sourceFolder = null;
        Folder targetFolder = null;
        try {
            sourceFolder = storeCopier.getSourceStore().getFolder(sourceFolderName);
            try {
                sourceFolder.open(Folder.READ_WRITE);
            } catch (ReadOnlyFolderException ex) {
                sourceFolder.open(Folder.READ_ONLY);
            }
            final Message[] sourceMessages = sourceFolder.getMessages(start, end);
            sourceFolder.fetch(sourceMessages, MessageId.addHeaders(new FetchProfile()));

            final List<Message> toCopy = new ArrayList<>();
            final List<MessageUpdate> updates = new ArrayList<>();
            for (Message message : sourceMessages) {
                try {
                    final MessageId id = new MessageId(message);
                    //Index message for deletion (if necessary)
                    if (storeCopier.getSourceIndex() != null) {
                        storeCopier.getSourceIndex().getFolderMessages(sourceFolderName).add(id);
                    }
                    if (!targetFolderMessages.contains(id)) {
                        ((IMAPMessage) message).setPeek(true);
                        toCopy.add(message);
                    } else {
                        ((IMAPMessage) message).setPeek(true);
                        final MessageState targetState = storeCopier.getTargetIndex()
                            .getMessageState(targetFolderName, id);
                        if (targetState == null) {
                            skipped++;
                        } else {
                            final boolean contentChanged = !targetState.hasSameContent(message);
                            updates.add(new MessageUpdate(message, targetState, contentChanged));
                            if (contentChanged) {
                                continue;
                            }
                            skipped++;
                        }
                    }
                } catch (MessageId.MessageIdException ex) {
                    //An unidentifiable source message must never make its target counterpart deletable.
                    if (storeCopier.getSourceIndex() != null) {
                        storeCopier.getSourceIndex().markFolderUnsafeForDeletion(sourceFolderName);
                    }
                    skipped++;
                }
            }
            if (!toCopy.isEmpty() || !updates.isEmpty()) {
                final FetchProfile fullProfile = MessageId.addHeaders(new FetchProfile());
                fullProfile.add(FetchProfile.Item.CONTENT_INFO);
                fullProfile.add(FetchProfile.Item.FLAGS);
                fullProfile.add(IMAPFolder.FetchProfileItem.HEADERS);
                fullProfile.add(FetchProfile.Item.SIZE);
                final List<Message> messagesToFetch = new ArrayList<>(toCopy);
                for (MessageUpdate update : updates) {
                    messagesToFetch.add(update.source);
                }
                sourceFolder.fetch(messagesToFetch.toArray(new Message[0]), fullProfile);
                targetFolder = storeCopier.getTargetStore().getFolder(targetFolderName);
                targetFolder.open(Folder.READ_WRITE);
                for (Message message : toCopy) {
                    targetFolder.appendMessages(new Message[]{message});
                    try {
                        targetFolderMessages.add(new MessageId(message));
                        copied++;
                        if (copied % updateCount == 0) {
                            storeCopier.updatedMessagesCopiedCount(copied);
                            copied = 0L;
                        }
                    } catch (MessageId.MessageIdException ex) {
                        //No exception should be thrown because id was generated previously and worked
                        Logger.getLogger(StoreCopier.class.getName()).
                                log(Level.SEVERE, null, ex);
                    }
                }
                for (MessageUpdate update : updates) {
                    if (!update.contentChanged) {
                        continue;
                    }
                    final Message targetMessage = getTargetMessage(targetFolder, update.targetState);
                    targetFolder.appendMessages(new Message[]{update.source});
                    targetMessage.setFlag(Flags.Flag.DELETED, true);
                    expungeTarget = true;
                    copied++;
                }
                for (MessageUpdate update : updates) {
                    if (update.contentChanged) {
                        continue;
                    }
                    synchronizeFlags(update.source, getTargetMessage(targetFolder, update.targetState),
                        targetFolder);
                }
            }
        } catch (MessagingException messagingException) {
            storeCopier.getCopyExceptions().add(messagingException);
            Logger.getLogger(Index.class.getName()).log(Level.SEVERE, null,
                    messagingException);
        } finally {
            closeFolder(targetFolder, expungeTarget);
            closeFolder(sourceFolder, false);
        }
        storeCopier.updatedMessagesCopiedCount(copied);
        storeCopier.updateMessagesSkippedCount(skipped);
        if (storeCopier.getSourceIndex() != null) {
            //Quick way to update count (not precise)
            storeCopier.getSourceIndex().updatedIndexedMessageCount(copied + skipped);
        }
    }

    private static Message getTargetMessage(Folder targetFolder, MessageState targetState)
        throws MessagingException {

        if (!(targetFolder instanceof IMAPFolder)) {
            throw new MessagingException("Target folder does not support IMAP UIDs");
        }
        final Message targetMessage = ((IMAPFolder) targetFolder).getMessageByUID(targetState.getUid());
        if (targetMessage == null) {
            throw new MessagingException("Target message no longer exists");
        }
        return targetMessage;
    }

    private static void synchronizeFlags(Message sourceMessage, Message targetMessage, Folder targetFolder)
        throws MessagingException {

        final Flags permanentFlags = targetFolder.getPermanentFlags();
        for (Flags.Flag flag : permanentFlags.getSystemFlags()) {
            if (flag != Flags.Flag.DELETED && flag != Flags.Flag.RECENT && flag != Flags.Flag.USER) {
                targetMessage.setFlag(flag, sourceMessage.isSet(flag));
            }
        }
        final Set<String> userFlags = new HashSet<>(Arrays.asList(permanentFlags.getUserFlags()));
        if (permanentFlags.contains(Flags.Flag.USER)) {
            userFlags.addAll(Arrays.asList(sourceMessage.getFlags().getUserFlags()));
            userFlags.addAll(Arrays.asList(targetMessage.getFlags().getUserFlags()));
        }
        for (String flag : userFlags) {
            targetMessage.setFlags(new Flags(flag), sourceMessage.getFlags().contains(flag));
        }
    }

    private void closeFolder(Folder folder, boolean expunge) {
        if (folder != null && folder.isOpen()) {
            try {
                folder.close(expunge);
            } catch (MessagingException messagingException) {
                storeCopier.getCopyExceptions().add(messagingException);
                Logger.getLogger(Index.class.getName()).log(Level.SEVERE, null, messagingException);
            }
        }
    }

    private static final class MessageUpdate {
        private final Message source;
        private final MessageState targetState;
        private final boolean contentChanged;

        private MessageUpdate(Message source, MessageState targetState, boolean contentChanged) {
            this.source = source;
            this.targetState = targetState;
            this.contentChanged = contentChanged;
        }
    }
}

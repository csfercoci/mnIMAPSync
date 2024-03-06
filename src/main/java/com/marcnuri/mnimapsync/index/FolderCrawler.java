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
package com.marcnuri.mnimapsync.index;

import jakarta.mail.*;

import java.sql.Connection;

import jakarta.mail.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class FolderCrawler implements Runnable {

    private final Store store;
    private final String folderName;
    private final int start;
    private final int end;
    private final Index index;
    private final Connection connection;

    protected FolderCrawler(Store store, String folderName, int start, int end,
                            Index index, Connection connection) {
        this.store = store;
        this.folderName = folderName;
        this.start = start;
        this.end = end;
        this.index = index;
        this.connection = connection;
    }

    public void run() {
        long indexedMessages = 0L;
        long skippedMessages = 0L;
        try {
            final Folder folder = store.getFolder(folderName);
            folder.open(Folder.READ_ONLY);
            final Message[] messages = folder.getMessages(start, end);
            folder.fetch(messages, MessageId.addHeaders(new FetchProfile()));
            for (Message message : messages) {
                if (index.hasCrawlException()) {
                    return;
                }
                try {
                    final MessageId messageId = new MessageId(message);
                    if (index.getFolderMessages(folderName).add(messageId)) {
                        indexedMessages++;
                      /*  // Insert message into database
                        try (PreparedStatement statement = connection.prepareStatement(
                                "MERGE INTO messages (folder_name, message_id) KEY(message_id) VALUES (?, ?)")) {
                            statement.setString(1, folderName);
                            statement.setString(2, messageId.toString());
                            statement.executeUpdate();
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }

                       */
                    } else {
                        skippedMessages++;
                    }
                } catch (MessageId.MessageIdException ex) {
                    if (ex.getCause() != null) {
                        throw new MessagingException();
                    }
                    skippedMessages++;
                }
            }
            folder.close(false);
        } catch (MessagingException  messagingException) {
            index.addCrawlException(messagingException);
        }
        index.updatedIndexedMessageCount(indexedMessages);
        index.updatedSkippedMessageCount(skippedMessages);
    }
}

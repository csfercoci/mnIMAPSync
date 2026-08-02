/*
 * IMAPUtils.java
 *
 * Created on 2019-08-31, 9:33
 *
 * Copyright 2019 Marc Nuri San Felix
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
package com.marcnuri.mnimapsync.imap;

import com.marcnuri.mnimapsync.HostDefinition;
import com.marcnuri.mnimapsync.index.Index;
import com.sun.mail.imap.IMAPSSLStore;
import com.sun.mail.imap.IMAPStore;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;

import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.Properties;


/**
 * Created by Marc Nuri <marc@marcnuri.com> on 2019-08-31.
 */
public class IMAPUtils {

  public static final String INBOX_MAILBOX = "INBOX";

  private IMAPUtils() {
  }

  /**
   * Open an {@link IMAPStore} for the provided {@link HostDefinition}
   *
   * @param hostDefinition for the IMAPStore connection
   * @param threads that will be consuming the IMAPStore connection
   * @return the open IMAPStore
   */
  public static IMAPStore openStore(HostDefinition hostDefinition, int threads)
      throws MessagingException, GeneralSecurityException {
    final Properties properties = new Properties();
    properties.put("mail.debug", "false");
    final String protocol = hostDefinition.isSsl() ? "imaps" : "imap";
    properties.put("mail.imap.starttls.enable", true);
    properties.put("mail.mime.address.strict", false);
    properties.put("mail.mime.allowutf8", true);
    properties.put("mail.mime.address.usecanonicalhostname",true);
    properties.put("mail.mime.decodeparameters",true);
    properties.put("mail.mime.encodeparameters",true);
    properties.put("mail.mime.contentdisposition.strict", "false"); // default true
    properties.put("mail.mime.charset", "UTF-8"); // Set character encoding
    properties.setProperty("mail." + protocol + ".connectionpoolsize", String.valueOf(threads));
    properties.setProperty("mail." + protocol + ".connectiontimeout",
        String.valueOf(hostDefinition.getConnectTimeout()));
    properties.setProperty("mail." + protocol + ".timeout", String.valueOf(hostDefinition.getReadTimeout()));
    properties.setProperty("mail." + protocol + ".writetimeout",
        String.valueOf(hostDefinition.getReadTimeout()));
    if (hostDefinition.isSsl()) {
      properties.put("mail.imaps.ssl.enable", true);
      properties.put("mail.imaps.ssl.checkserveridentity", true);
    }
    final Session session = Session.getInstance(properties, null);
    MessagingException lastFailure = null;
    for (int attempt = 0; attempt <= hostDefinition.getRetries(); attempt++) {
      IMAPStore store = null;
      try {
        store = hostDefinition.isSsl()
            ? (IMAPSSLStore) session.getStore(protocol)
            : (IMAPStore) session.getStore(protocol);
        store.connect(hostDefinition.getHost(), hostDefinition.getPort(), hostDefinition.getUser(),
            hostDefinition.getPassword());
        return store;
      } catch (AuthenticationFailedException exception) {
        closeStore(store);
        throw exception;
      } catch (MessagingException exception) {
        closeStore(store);
        lastFailure = exception;
        if (attempt < hostDefinition.getRetries()) {
          waitBeforeRetry(attempt);
        }
      }
    }
    throw lastFailure;
  }

  private static void closeStore(IMAPStore store) {
    if (store != null && store.isConnected()) {
      try {
        store.close();
      } catch (MessagingException ignored) {
        // Preserve the connection failure that caused this cleanup.
      }
    }
  }

  private static void waitBeforeRetry(int attempt) throws MessagingException {
    try {
      Thread.sleep(1000L * (attempt + 1));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new MessagingException("Interrupted while waiting to reconnect", exception);
    }
  }

  private static Optional<String> translateInbox(String folderName, String inboxName) {
    if (INBOX_MAILBOX.equalsIgnoreCase(folderName)) {
      return Optional.ofNullable(inboxName);
    }
    return Optional.empty();
  }

  private static String translateFolder(String folderName, Index sourceIndex, Index targetIndex) {
    return folderName.replace(sourceIndex.getFolderSeparator(), targetIndex.getFolderSeparator());
  }

  public static String sourceFolderNameToTarget(String sourceFolderFullName,
      Index sourceIndex, Index targetIndex) {

    return translateInbox(sourceFolderFullName, targetIndex.getInbox())
        .orElse(translateFolder(sourceFolderFullName, sourceIndex, targetIndex));
  }
  public static String targetToSourceFolderName(String targetFolderFullName,
      Index sourceIndex, Index targetIndex) {

    return translateInbox(targetFolderFullName, sourceIndex.getInbox())
        .orElse(translateFolder(targetFolderFullName, targetIndex, sourceIndex));
  }
}

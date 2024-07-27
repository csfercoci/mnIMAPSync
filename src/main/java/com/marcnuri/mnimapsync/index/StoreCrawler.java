package com.marcnuri.mnimapsync.index;

import com.marcnuri.mnimapsync.MNIMAPSync;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class StoreCrawler {

  private static final String JDBC_URL = "jdbc:h2:~/mnimapsync-db";

  private StoreCrawler() {
  }

  public static Index populateFromStore(Index index, Store store, int threads)
          throws MessagingException, InterruptedException, SQLException {
    try (Connection connection = DriverManager.getConnection(JDBC_URL)) {
      connection.setAutoCommit(false);
      createTablesIfNotExists(connection);
      // Populate index from store
      index.setFolderSeparator(String.valueOf(store.getDefaultFolder().getSeparator()));
      ExecutorService service = Executors.newFixedThreadPool(threads);
      try {
        crawlFolders(store, index, store.getDefaultFolder(), service, connection);
      } catch (MessagingException ex) {
        connection.rollback();
        throw ex;
      }
      service.shutdown();
      service.awaitTermination(1, TimeUnit.HOURS);
      if (index.hasCrawlException()) {
        connection.rollback();
        throw index.getCrawlExceptions().iterator().next();
      }
      connection.commit();
    }
    return index;
  }

  private static void createTablesIfNotExists(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
            "CREATE TABLE IF NOT EXISTS folders (name VARCHAR(255) PRIMARY KEY, separator CHAR(1))")) {
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement(
            "CREATE TABLE IF NOT EXISTS messages (message_id VARCHAR(255) PRIMARY KEY, folder_name CHAR(255))")) {
      statement.executeUpdate();
    }
  }

  private static void crawlFolders(Store store, Index index, Folder folder, ExecutorService service,
                                   Connection connection) throws MessagingException, SQLException {
    if (folder != null ) {
      final String folderName = folder.getFullName();
      if (!index.containsFolder(folderName)){
        index.addFolder(folderName);
        // Insert folder info into database
        try (PreparedStatement statement = connection.prepareStatement(
                "MERGE INTO folders (name, separator) KEY(name) VALUES (?, ?)")) {
          statement.setString(1, folderName);
          statement.setString(2, String.valueOf(folder.getSeparator()));
          statement.executeUpdate();
        }
      }

      if ((folder.getType() & Folder.HOLDS_MESSAGES) == Folder.HOLDS_MESSAGES) {
        folder.open(Folder.READ_ONLY);
        if (folder.getMode() != Folder.READ_ONLY) {
          folder.expunge();
        }
         int messageCount = folder.getMessageCount();
         folder.close(false);
        int pos = 1;
        while (pos + MNIMAPSync.BATCH_SIZE <= messageCount) {
          service.execute(new FolderCrawler(store, folderName, pos,pos + MNIMAPSync.BATCH_SIZE, index, connection));
          pos = pos + MNIMAPSync.BATCH_SIZE;
        }
        service.execute(new FolderCrawler(store, folderName, pos, messageCount, index, connection));
      }
      // Folder recursion. Get all children
      if ((folder.getType() & Folder.HOLDS_FOLDERS) == Folder.HOLDS_FOLDERS) {
        for (Folder child : folder.list()) {
          crawlFolders(store, index, child, service, connection);
        }
      }
    }
  }
}


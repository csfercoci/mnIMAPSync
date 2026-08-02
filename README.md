# [mnIMAPSync](http://www.marcnuri.com/)

## Description
Java-based, one-way IMAP synchronization tool. It copies an IMAP source account to a target
account, making it suitable for migration and backup workflows.

This tool is inspired in [imapsync](http://imapsync.lamiral.info/). mnIMAPSync is still in
the early stages although it has been tested in several production systems, it may
fail in servers where it has not been tested yet. In the last part of this document there is a list of 
tested IMAP servers, please notify me if you successfully use mnIMAPSync with another server or 
create an issue for failing servers.

mnIMAPSync can be used to migrate or backup an IMAP account to another either in the same server or
 in different servers. The program can be run standalone (command line interface) or accessed directly 
from java.

## Features
- One-way source-to-target folder and message synchronization.
- Recursive folder creation with IMAP folder separator translation.
- Incremental copy: existing logical messages are not copied again.
- Message-body and attachment change detection using a SHA-256 content digest.
- Safe replacement of changed messages: append the replacement before deleting the old copy.
- Synchronization of permanent IMAP flags for unchanged messages.
- Optional deletion of target-only messages and folders.
- Bounded connection retry with configurable connect, read, and write timeouts.
- SSL/TLS with certificate and server identity validation.
- Multithreaded indexing and copying.
- Command-line execution and Java API access.

## Requirements
- Java 8 or later to run the application.
- Java 17 or later to build with the included Gradle 9.1 wrapper.

## Releases
- [0.0.4 beta] add utf8 support for subject and different helpers(charse,address loading)
- [0.0.3-alpha](https://github.com/manusa/mnIMAPSync/releases/download/0.0.3-alpha/mnIMAPSync-0.0.3-alpha.zip)
- [0.0.2-alpha](https://github.com/manusa/mnIMAPSync/releases/download/0.0.2-alpha/mnIMAPSync-0.0.2-alpha.zip)
- [0.0.1-alpha](https://github.com/manusa/mnIMAPSync/releases/download/0.01-alpha/mnIMAPSync-0.0.1-alpha.zip)

## Usage
The easiest way is to launch the program from the command-line interface.
Download the [latest binary release](https://github.com/manusa/mnIMAPSync/releases/tag/v0.0.4)
 and execute as follows:

```Batchfile
java -jar mnIMAPSync.jar --host1 imap.gmail.com --port1 993 --user1 user@gmail.com --password1 password --ssl1 --host2 other.server.com --port2 993 --user2 user2@other.server.com --password2 password2 --ssl2 --threads 4 --retries 3 --connect-timeout 30000 --read-timeout 60000 --delete
```

You can also use any of the convenient shell scripts bundled in the distribution to avoid typing 
`java -jar mnIMAPSync.jar`.

You can find a tutorial on how to use and install mnIMAPSync at 
There is an instructable available at [blog.marcnuri.com](http://blog.marcnuri.com/incremental-e-mail-backup-and-migration-using-mnimapsync/)

### Command-line arguments
|Option|Description|
|------|-----------|
|`--host1`*|Host of the source mail server.|
|`--port1`*|IMAP port of the source mail server.|
|`--user1`*|User name for the account on the source mail server.|
|`--password1`*|Password for the account on the source mail server.|
|`--password1-env`|Environment variable containing the source password; use instead of `--password1` to keep it out of the process arguments.|
|`--ssl1`|Optional parameter indicating if the program should connect using SSL to the source server.|
|`--host2`*|Host of the target mail server.|
|`--port2`*|IMAP port of the target mail server.|
|`--user2`*|User name for the account on the target mail server.|
|`--password2`*|Password for the account on the target mail server.|
|`--password2-env`|Environment variable containing the target password; use instead of `--password2` to keep it out of the process arguments.|
|`--ssl2`|Optional parameter indicating if the program should connect using SSL to the target server.|
|`--threads`|Number of threads to use. Some servers limit concurrent IMAP connections. Default: `5`.|
|`--delete`|Delete messages and folders in the target that do not exist in the source. Disabled by default.|
|`--retries`|Number of retries after an initial transient connection failure. Default: `3`. Authentication failures are never retried.|
|`--connect-timeout`|Connection timeout in milliseconds, applied to both hosts. Default: `30000`.|
|`--read-timeout`|Read and write timeout in milliseconds, applied to both hosts. Default: `60000`.|
|`--watch`|Keep a source IMAP `IDLE` connection open and synchronize immediately after source changes.|
|`--watch-folder`|Restrict IMAP `IDLE` to one source folder. Without it, all source message folders are monitored.|
|`--watch-interval`|Fallback full-sync interval in seconds while watching. Default: `300`.|
\*Required arguments

### Credentials and TLS

Passwords passed as `--password1` and `--password2` can be visible to other local users through
the process list. Prefer dedicated migration credentials with restricted access and revoke them
after the operation.

When `--ssl1` or `--ssl2` is enabled, the server certificate and hostname are validated. Install
the appropriate CA certificate in the Java trust store for private or self-signed IMAP servers;
the application does not provide an insecure "trust all certificates" mode.

## Synchronization semantics

mnIMAPSync is not bidirectional. `host1` is always the source and `host2` is always the target.

- Missing source folders are created in the target.
- Messages that do not exist in the target are appended.
- Messages with the same logical identity and different body content are replaced. The new message
  is appended before the old target message is marked for deletion.
- For messages with matching content, permanent flags supported by the target server are mirrored.
  Server-owned `RECENT` and destructive `DELETED` flags are intentionally not mirrored.
- With `--delete`, target messages and folders that are not present in the source are removed.

Message identity is derived from `Message-ID`, sender, recipients, and subject to avoid duplicates
when servers normalize headers differently. A subject, sender, or recipient change therefore has a
different identity and is treated as a new message. When `--delete` is enabled, the old target
message is removed during the deletion phase.

Deletion is intentionally conservative. If a source message cannot be identified reliably, delete
operations are skipped for that folder. Deletion is also skipped entirely when copying reports an
IMAP error.

### Connection behavior

Each store connection has bounded connect/read/write timeouts. Transient connection failures are
retried with linear backoff: one second before the first retry, two seconds before the second, and
so on. Authentication errors fail immediately. A later IMAP operation that fails stops the copy
phase and prevents the optional delete phase, so it cannot cause cleanup from an incomplete source
view.

### Continuous synchronization with IMAP IDLE

Use `--watch` to run an initial synchronization and then keep every source folder that contains
messages open in IMAP `IDLE` mode:

```Batchfile
java -jar mnIMAPSync.jar --host1 imap.example.com --port1 993 --user1 source --password1 password --ssl1 --host2 backup.example.com --port2 993 --user2 target --password2 password --ssl2 --watch --watch-interval 300
```

New messages, message removals, and server-reported flag changes trigger an on-the-fly sync. Events
received while a sync is running are coalesced into one additional sync after it completes. The
source `IDLE` connections reconnect after an interruption, and the fallback interval refreshes the
folder list so newly created source folders are included.

IMAP requires a selected connection for each concurrently watched folder. Accounts with many
folders can exceed their server connection limit. Use `--watch-folder <folder>` to monitor only a
specific folder, typically `INBOX`, and rely on `--watch-interval` for periodic synchronization of
the remaining folders.

## Motivation
When using [imapsync](http://imapsync.lamiral.info/) to sync different servers I'm getting lots of 
duplicate messages in successive runs. This is due to the fact that when dealing with unconventional
e-mails, each server stores certain header information in its own way.

This program is based on maximizing performance while avoiding duplicates caused by server-specific
header normalization.

## Syncing process

### Target indexing

The process starts indexing mail messages and IMAP folders in the target server. This information
will be used later to check if the target server already contains messages we are copying from the source 
server.

The index is created per folder. Every target message is assigned a logical identity, target UID, and
content digest. The UID is used to update its flags or safely replace a changed message.

### Copy process

Once the target index is completed, if and only if this process was successful, the copy process begins.
If there were errors indexing the target the copying process will abort, not aborting could mean duplicating
messages in the target server.

After indexing, folders are created and source messages are processed in non-overlapping batches of
200 messages. Once copying finishes without IMAP errors, the optional deletion phase runs.

## Tested Servers
- [Dovecot](http://www.dovecot.org)
- [hMailServer](http://www.hmailserver.com)
- [Gmail](http://mail.google.com)
- [Zimbra Collaboration](http://www.zimbra.com)
- [Yahoo](http://mail.yahoo.com) 

Please, share your experience with any other server.

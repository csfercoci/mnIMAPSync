/*
 * ArgumentParser.java
 *
 * Created on 2019-08-30, 8:08
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
package com.marcnuri.mnimapsync.cli;

import com.marcnuri.mnimapsync.SyncOptions;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.MissingArgumentException;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.UnrecognizedOptionException;

public class ArgumentParser {
  public static SyncOptions parseCliArguments(String[] arguments) {
    Options options = createOptions();
    CommandLineParser parser = new DefaultParser();
    SyncOptions result = new SyncOptions();

    try {
      CommandLine cmd = parser.parse(options, arguments);

      result.getSourceHost().setHost(cmd.getOptionValue("host1"));
      result.getSourceHost().setPort(parsePortValue("port1", cmd.getOptionValue("port1")));
      result.getSourceHost().setUser(cmd.getOptionValue("user1"));
      result.getSourceHost().setPassword(cmd.getOptionValue("password1"));
      result.getSourceHost().setSsl(cmd.hasOption("ssl1"));

      result.getTargetHost().setHost(cmd.getOptionValue("host2"));
      result.getTargetHost().setPort(parsePortValue("port2", cmd.getOptionValue("port2")));
      result.getTargetHost().setUser(cmd.getOptionValue("user2"));
      result.getTargetHost().setPassword(cmd.getOptionValue("password2"));
      result.getTargetHost().setSsl(cmd.hasOption("ssl2"));

      if (cmd.hasOption("retries")) {
        final int retries = parseNonNegativeIntValue("retries", cmd.getOptionValue("retries"));
        result.getSourceHost().setRetries(retries);
        result.getTargetHost().setRetries(retries);
      }
      if (cmd.hasOption("connect-timeout")) {
        final int timeout = parsePositiveIntValue("connect-timeout",
            cmd.getOptionValue("connect-timeout"));
        result.getSourceHost().setConnectTimeout(timeout);
        result.getTargetHost().setConnectTimeout(timeout);
      }
      if (cmd.hasOption("read-timeout")) {
        final int timeout = parsePositiveIntValue("read-timeout", cmd.getOptionValue("read-timeout"));
        result.getSourceHost().setReadTimeout(timeout);
        result.getTargetHost().setReadTimeout(timeout);
      }

      result.setDelete(cmd.hasOption("delete"));
      if (cmd.hasOption("threads")) {
        result.setThreads(parsePositiveIntValue("threads", cmd.getOptionValue("threads")));
      }

    } catch (ParseException e) {
      throw toIllegalArgumentException(e);
    }

    return result;
  }

  private static Options createOptions() {
    Options options = new Options();

    options.addOption(Option.builder().longOpt("host1").hasArg().desc("Source host").required().build());
    options.addOption(Option.builder().longOpt("port1").hasArg().desc("Source port").required().build());
    options.addOption(Option.builder().longOpt("user1").hasArg().desc("Source user").required().build());
    options.addOption(Option.builder().longOpt("password1").hasArg().desc("Source password").required().build());
    options.addOption(Option.builder().longOpt("ssl1").desc("Enable SSL for source").build());

    options.addOption(Option.builder().longOpt("host2").hasArg().desc("Target host").required().build());
    options.addOption(Option.builder().longOpt("port2").hasArg().desc("Target port").required().build());
    options.addOption(Option.builder().longOpt("user2").hasArg().desc("Target user").required().build());
    options.addOption(Option.builder().longOpt("password2").hasArg().desc("Target password").required().build());
    options.addOption(Option.builder().longOpt("ssl2").desc("Enable SSL for target").build());

    options.addOption(Option.builder().longOpt("delete").desc("Enable delete operation").build());
    options.addOption(Option.builder().longOpt("threads").hasArg().desc("Number of threads").build());
    options.addOption(Option.builder().longOpt("retries").hasArg().desc("Connection retries").build());
    options.addOption(Option.builder().longOpt("connect-timeout").hasArg()
        .desc("Connection timeout in milliseconds").build());
    options.addOption(Option.builder().longOpt("read-timeout").hasArg()
        .desc("Read and write timeout in milliseconds").build());

    return options;
  }

  private static IllegalArgumentException toIllegalArgumentException(ParseException exception) {
    if (exception instanceof MissingArgumentException) {
      final MissingArgumentException missingArgumentException = (MissingArgumentException) exception;
      return new IllegalArgumentException("--" + missingArgumentException.getOption().getLongOpt()
          + " requires a value", exception);
    }
    if (exception instanceof UnrecognizedOptionException) {
      final UnrecognizedOptionException unrecognizedOptionException =
          (UnrecognizedOptionException) exception;
      return new IllegalArgumentException("Unrecognized argument: "
          + unrecognizedOptionException.getOption(), exception);
    }
    return new IllegalArgumentException(exception.getMessage(), exception);
  }

  private static int parsePortValue(String key, String intValue) {
    final int port = parsePositiveIntValue(key, intValue);
    if (port > 65535) {
      throw new IllegalArgumentException("--" + key + " must be between 1 and 65535");
    }
    return port;
  }

  private static int parsePositiveIntValue(String key, String intValue) {
    final int value = parseNonNegativeIntValue(key, intValue);
    if (value < 1) {
      throw new IllegalArgumentException("--" + key + " must be greater than zero");
    }
    return value;
  }

  private static int parseNonNegativeIntValue(String key, String intValue) {
    try {
      final int value = Integer.parseInt(intValue);
      if (value < 0) {
        throw new IllegalArgumentException("--" + key + " must not be negative");
      }
      return value;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--" + key + " value should be an integer", e);
    }
  }
}

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

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;

import org.apache.commons.cli.*;

public class ArgumentParser {
  public static SyncOptions parseCliArguments(String[] arguments) {
    Options options = createOptions();
    CommandLineParser parser = new DefaultParser();
    SyncOptions result = new SyncOptions();

    try {
      CommandLine cmd = parser.parse(options, arguments);

      result.getSourceHost().setHost(cmd.getOptionValue("host1"));
      result.getSourceHost().setPort(parseIntValue("port1", cmd.getOptionValue("port1")));
      result.getSourceHost().setUser(cmd.getOptionValue("user1"));
      result.getSourceHost().setPassword(cmd.getOptionValue("password1"));
      result.getSourceHost().setSsl(cmd.hasOption("ssl1"));

      result.getTargetHost().setHost(cmd.getOptionValue("host2"));
      result.getTargetHost().setPort(parseIntValue("port2", cmd.getOptionValue("port2")));
      result.getTargetHost().setUser(cmd.getOptionValue("user2"));
      result.getTargetHost().setPassword(cmd.getOptionValue("password2"));
      result.getTargetHost().setSsl(cmd.hasOption("ssl2"));

      result.setDelete(cmd.hasOption("delete"));
      result.setThreads(parseIntValue("threads", cmd.getOptionValue("threads")));

    } catch (ParseException e) {
      System.err.println("Parsing failed. Reason: " + e.getMessage());
      System.out.println("Parsing failed. Reason: " + e.getMessage());

      printHelp(options);

      System.exit(1);
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

    return options;
  }

  private static void printHelp(Options options) {
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp("java -jar your-application.jar", options);
  }
  private static int parseIntValue(String key, String intValue) {
    try {
      return Integer.parseInt(intValue);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(String.format("%s requires a valid integer as a value", key));
    }
  }



@FunctionalInterface
  private interface ParserAction {

    void action(String key);
  }
}

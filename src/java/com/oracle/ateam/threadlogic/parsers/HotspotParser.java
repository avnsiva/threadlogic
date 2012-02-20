/**
 * Copyright (c) 2012 egross, sabha.
 * 
 * ThreadLogic - parses thread dumps and provides analysis/guidance
 * It is based on the popular TDA tool.  Thank you!
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 */
/*
 * SunJDKParser.java
 *
 * This file is part of TDA - Thread Dump Analysis Tool.
 *
 * TDA is free software; you can redistribute it and/or modify
 * it under the terms of the Lesser GNU General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * TDA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Lesser GNU General Public License for more details.
 *
 * You should have received a copy of the Lesser GNU General Public License
 * along with TDA; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 *
 * $Id: SunJDKParser.java,v 1.47 2010-01-03 14:23:09 irockel Exp $
 */
package com.oracle.ateam.threadlogic.parsers;

import com.oracle.ateam.threadlogic.HeapInfo;
import com.oracle.ateam.threadlogic.HistogramInfo;
import com.oracle.ateam.threadlogic.ThreadLogic;
import com.oracle.ateam.threadlogic.ThreadDumpInfo;
import com.oracle.ateam.threadlogic.categories.TreeCategory;
import com.oracle.ateam.threadlogic.monitors.MonitorMap;
import com.oracle.ateam.threadlogic.utils.DateMatcher;
import com.oracle.ateam.threadlogic.utils.HistogramTableModel;
import com.oracle.ateam.threadlogic.utils.IconFactory;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;

/**
 * Parses SunJDK Thread Dumps. Also parses SAP and HP Dumps. Needs to be closed
 * after use (so inner stream is closed).
 * 
 * @author irockel
 */
public class HotspotParser extends AbstractDumpParser {

  private int counter = 1;
  private int lineCounter = 0;
  private boolean foundClassHistograms = false;
  private boolean withCurrentTimeStamp = false;

  /**
   * Creates a new instance of SunJDKParser
   */
  public HotspotParser(BufferedReader bis, Map threadStore, int lineCounter, boolean withCurrentTimeStamp,
      int startCounter, DateMatcher dm) {
    super(bis, dm);
    this.threadStore = threadStore;
    this.withCurrentTimeStamp = withCurrentTimeStamp;
    this.lineCounter = lineCounter;
    this.counter = startCounter;
    this.lineChecker = new LineChecker();
    this.lineChecker.setFullDumpPattern("(.*Full thread dump.*)");
    this.lineChecker.setAtPattern("(.*at.*)");
    this.lineChecker.setThreadStatePattern("(.*java.lang.Thread.State.*)");
    this.lineChecker.setLockedOwnablePattern("(.*Locked ownable synchronizers:.*)");
    this.lineChecker.setWaitingOnPattern("(.*- waiting on.*)");
    this.lineChecker.setParkingToWaitPattern("(.*- parking to wait.*)");
    this.lineChecker.setWaitingToPattern("(.*- waiting to.*)");
    this.lineChecker.setLockedPattern("(.*- locked.*)");
    this.lineChecker.setEndOfDumpPattern(".*(VM Periodic Task Thread|Suspend Checker Thread|<EndOfDump>).*");
  }

  /**
   * @returns true, if a class histogram was found and added during parsing.
   */
  public boolean isFoundClassHistograms() {
    return (foundClassHistograms);
  }

  public MutableTreeNode parseNext() {
    this.mmap = new MonitorMap();
    return super.parseNext();
  }

  /**
   * add a monitor link for monitor navigation
   * 
   * @param line
   *          containing monitor
   */
  protected String linkifyMonitor(String line) {
    if (line != null && line.indexOf('<') >= 0) {
      String begin = line.substring(0, line.indexOf('<'));
      String monitor = line.substring(line.indexOf('<'), line.indexOf('>') + 1);
      String end = line.substring(line.indexOf('>') + 1);
      monitor = monitor.replaceAll("<", "<a href=\"monitor://" + monitor + "\">&lt;");
      monitor = monitor.substring(0, monitor.length() - 1) + "&gt;</a>";
      return (begin + monitor + end);
    } else if (line != null && line.indexOf('@') >= 0) {
      String begin = line.substring(0, line.indexOf('@') + 1);
      String monitor = line.substring(line.indexOf('@'));
      monitor = monitor.replaceAll("@", "@<a href=\"monitor://<" + monitor.substring(1) + ">\">");
      monitor = monitor.substring(0, monitor.length() - 1) + "</a>";
      return (begin + monitor);
    } else {
      return (line);
    }
  }

  /**
   * add a monitor link for monitor navigation
   * 
   * @param line
   *          containing monitor
   */
  public String linkifyDeadlockInfo(String line) {
    if (line != null && line.indexOf("Ox") >= 0) {
      String begin = line.substring(0, line.indexOf("0x"));
      int objectBegin = line.lastIndexOf("0x");
      int monitorBegin = line.indexOf("0x");
      String monitorHex = line.substring(monitorBegin, monitorBegin + 10);

      String monitor = line.substring(objectBegin, objectBegin + 10);
      String end = line.substring(line.indexOf("0x") + 10);

      monitor = "<a href=\"monitor://<" + monitor + ">\">" + monitorHex + "</a>";
      return (begin + monitor + end);
    } else {
      return (line);
    }
  }

  /**
   * checks for the next class histogram and adds it to the tree node passed
   * 
   * @param threadDump
   *          which tree node to add the histogram.
   */
  public boolean checkForClassHistogram(DefaultMutableTreeNode threadDump) throws IOException {
    HistogramTableModel classHistogram = parseNextClassHistogram(getBis());

    if (classHistogram.getRowCount() > 0) {
      addHistogramToDump(threadDump, classHistogram);
    }

    return (classHistogram.getRowCount() > 0);
  }

  private void addHistogramToDump(DefaultMutableTreeNode threadDump, HistogramTableModel classHistogram) {
    DefaultMutableTreeNode catHistogram;
    HistogramInfo hi = new HistogramInfo("Class Histogram of Dump", classHistogram);
    catHistogram = new DefaultMutableTreeNode(hi);
    threadDump.add(catHistogram);
  }

  /**
   * parses the next class histogram found in the stream, uses the max check
   * lines option to check how many lines to parse in advance.
   * <p>
   * This could be called from parseLoggcFile, which is outside our normal
   * calling stream. Thus, we have to pass in the BufferedReader. However, to
   * handle a WrappedSunJDKParser, we have to use getNextLine() if possible.
   * 
   * @param bis
   *          the stream to read.
   */
  private HistogramTableModel parseNextClassHistogram(BufferedReader bis) throws IOException {
    boolean finished = false;
    boolean found = false;
    HistogramTableModel classHistogram = new HistogramTableModel();
    int maxLinesCounter = 0;

    boolean isNormalBis = bis == getBis();

    while (bis.ready() && !finished) {
      String line = (isNormalBis) ? getNextLine().trim() : bis.readLine().trim();
      if (!found && !line.equals("")) {
        if (line.startsWith("num   #instances    #bytes  class name")) {
          found = true;
        } else if (maxLinesCounter >= getMaxCheckLines()) {
          finished = true;
        } else {
          maxLinesCounter++;
        }
      } else if (found) {
        if (line.startsWith("Total ")) {
          // split string.
          String newLine = line.replaceAll("(\\s)+", ";");
          String[] elems = newLine.split(";");
          classHistogram.setBytes(Long.parseLong(elems[2]));
          classHistogram.setInstances(Long.parseLong(elems[1]));
          finished = true;
        } else if (!line.startsWith("-------")) {
          // removed blank, breaks splitting using blank...
          String newLine = line.replaceAll("<no name>", "<no-name>");

          // split string.
          newLine = newLine.replaceAll("(\\s)+", ";");
          String[] elems = newLine.split(";");

          if (elems.length == 4) {
            classHistogram.addEntry(elems[3].trim(), Integer.parseInt(elems[2].trim()),
                Integer.parseInt(elems[1].trim()));
          } else {
            classHistogram.setIncomplete(true);
            finished = true;
          }

        }
      }
    }

    return (classHistogram);
  }

  /**
   * Heap PSYoungGen total 6656K, used 3855K [0xb0850000, 0xb0f50000,
   * 0xb4130000) eden space 6144K, 54% used [0xb0850000,0xb0b97740,0xb0e50000)
   * from space 512K, 97% used [0xb0ed0000,0xb0f4c5c0,0xb0f50000) to space 512K,
   * 0% used [0xb0e50000,0xb0e50000,0xb0ed0000) PSOldGen total 15552K, used
   * 13536K [0x94130000, 0x95060000, 0xb0850000) object space 15552K, 87% used
   * [0x94130000,0x94e68168,0x95060000) PSPermGen total 16384K, used 13145K
   * [0x90130000, 0x91130000, 0x94130000) object space 16384K, 80% used
   * [0x90130000,0x90e06610,0x91130000)
   * 
   * @param threadDump
   * @return
   * @throws java.io.IOException
   */
  public boolean checkThreadDumpStatData(ThreadDumpInfo tdi) throws IOException {
    boolean finished = false;
    boolean found = false;
    StringBuffer hContent = new StringBuffer();
    int heapLineCounter = 0;
    int lines = 0;

    while (getBis().ready() && !finished) {
      String line = getNextLine();
      if (!found && !line.equals("")) {
        if (line.trim().startsWith("Heap")) {
          found = true;
        } else if (lines >= getMaxCheckLines()) {
          finished = true;
        } else {
          lines++;
        }
      } else if (found) {
        if (heapLineCounter < 7) {
          hContent.append(line).append("\n");
        } else {
          finished = true;
        }
        heapLineCounter++;
      }
    }
    if (hContent.length() > 0) {
      tdi.setHeapInfo(new HeapInfo(hContent.toString()));
    }

    return (found);
  }

  /**
   * check if any dead lock information is logged in the stream
   * 
   * @param threadDump
   *          which tree node to add the histogram.
   */
  public int checkForDeadlocks(DefaultMutableTreeNode threadDump) throws IOException {
    boolean finished = false;
    boolean found = false;
    int deadlocks = 0;
    int lineCounter = 0;
    StringBuffer dContent = new StringBuffer();
    TreeCategory deadlockCat = new TreeCategory("Deadlocks", IconFactory.DEADLOCKS);
    DefaultMutableTreeNode catDeadlocks = new DefaultMutableTreeNode(deadlockCat);
    boolean first = true;

    while (getBis().ready() && !finished) {
      String line = getNextLine();

      if (!found && !line.equals("")) {
        if (line.trim().startsWith("Found one Java-level deadlock")) {
          found = true;
          dContent.append("<body bgcolor=\"ffffff\"><font size=").append(ThreadLogic.getFontSizeModifier(-1)).append("><b>");
          dContent.append("Found one Java-level deadlock");
          dContent.append("</b><hr></font><pre>\n");
        } else if (lineCounter >= getMaxCheckLines()) {
          finished = true;
        } else {
          lineCounter++;
        }
      } else if (found) {
        if (line.startsWith("Found one Java-level deadlock")) {
          if (dContent.length() > 0) {
            deadlocks++;
            addToCategory(catDeadlocks, "Deadlock No. " + (deadlocks), null, dContent.toString(), 0, false);
          }
          dContent = new StringBuffer();
          dContent.append("</pre><b><font size=").append(ThreadLogic.getFontSizeModifier(-1)).append(">");
          dContent.append("Found one Java-level deadlock");
          dContent.append("</b><hr></font><pre>\n");
          first = true;
        } else if ((line.indexOf("Found") >= 0) && (line.endsWith("deadlocks.") || line.endsWith("deadlock."))) {
          finished = true;
        } else if (line.startsWith("=======")) {
          // ignore this line
        } else if (line.indexOf(" monitor 0x") >= 0) {
          dContent.append(linkifyDeadlockInfo(line));
          dContent.append("\n");
        } else if (line.indexOf("Java stack information for the threads listed above") >= 0) {
          dContent.append("</pre><br><font size=").append(ThreadLogic.getFontSizeModifier(-1)).append("><b>");
          dContent.append("Java stack information for the threads listed above");
          dContent.append("</b><hr></font><pre>");
          first = true;
        } else if ((line.indexOf("- waiting on") >= 0) || (line.indexOf("- waiting to") >= 0)
            || (line.indexOf("- locked") >= 0) || (line.indexOf("- parking to wait") >= 0)) {

          dContent.append(linkifyMonitor(line));
          dContent.append("\n");

        } else if (line.trim().startsWith("\"")) {
          dContent.append("</pre>");
          if (first) {
            first = false;
          } else {
            dContent.append("<br>");
          }
          dContent.append("<b><font size=").append(ThreadLogic.getFontSizeModifier(-1)).append("><code>");
          dContent.append(line);
          dContent.append("</font></code></b><pre>");
        } else {
          dContent.append(line);
          dContent.append("\n");
        }
      }
    }
    if (dContent.length() > 0) {
      deadlocks++;
      addToCategory(catDeadlocks, "Deadlock No. " + (deadlocks), null, dContent.toString(), 0, false);
    }

    if (deadlocks > 0) {
      threadDump.add(catDeadlocks);
      ((ThreadDumpInfo) threadDump.getUserObject()).setDeadlocks((TreeCategory) catDeadlocks.getUserObject());
      deadlockCat.setName("Deadlocks (" + deadlocks + (deadlocks == 1 ? " deadlock)" : " deadlocks)"));
    }

    return (deadlocks);
  }

  /**
   * parses a loggc file stream and reads any found class histograms and adds
   * the to the dump store
   * 
   * @param loggcFileStream
   *          the stream to read
   * @param root
   *          the root node of the dumps.
   */
  public void parseLoggcFile(InputStream loggcFileStream, DefaultMutableTreeNode root) {
    BufferedReader bis = new BufferedReader(new InputStreamReader(loggcFileStream));
    Vector histograms = new Vector();

    try {
      while (bis.ready()) {
        bis.mark(getMarkSize());
        String nextLine = bis.readLine();
        if (nextLine.startsWith("num   #instances    #bytes  class name")) {
          bis.reset();
          histograms.add(parseNextClassHistogram(bis));
        }
      }

      // now add the found histograms to the tree.
      for (int i = histograms.size() - 1; i >= 0; i--) {
        DefaultMutableTreeNode dump = getNextDumpForHistogram(root);
        if (dump != null) {
          addHistogramToDump(dump, (HistogramTableModel) histograms.get(i));
        }
      }
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }

  /**
   * generate thread info token for table view.
   * 
   * @param name
   *          the thread info.
   * @return thread tokens.
   */
  public String[] getThreadTokens(String name) {
    
    String patternMask = "^.*\"([^\\\"]+)\".*tid=([^ ]+|).*nid=([^ ]+) *([^\\[]*).*";
    name = name.replace("- Thread t@", "tid=");
    
    String[] tokens = new String[] {};
   
    try {
      Pattern p = Pattern.compile(patternMask);
      Matcher m = p.matcher(name);

      System.out.println(m.matches());
      for (int iLoop = 1; iLoop < m.groupCount(); iLoop++) {
        System.out.println(iLoop + ": " + m.group(iLoop));
      }
    
      tokens = new String[7];
      tokens[0] = m.group(1); // name
      // tokens[1] = m.group(4); // prio
      tokens[1] = m.group(3); // tid
      tokens[2] = m.group(2); // nid
      tokens[3] = m.group(4); // State

    } catch(Exception e) { 
      
      System.out.println("WARNING!! Unable to parse Thread Tokens with name:" + name);
      e.printStackTrace();
    }
    
    return (tokens);
    
    
  }

  /**
   * check if the passed logline contains the beginning of a sun jdk thread
   * dump.
   * 
   * @param logLine
   *          the line of the logfile to test
   * @return true, if the start of a sun thread dump is detected.
   */
  public static boolean checkForSupportedThreadDump(String logLine) {
    return (logLine.trim().indexOf("Full thread dump") >= 0);
  }

}

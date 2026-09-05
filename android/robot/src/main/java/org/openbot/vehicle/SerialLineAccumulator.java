package org.openbot.vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** ASCII lines of at most 128 bytes including newline; discard through newline on overflow. */
public final class SerialLineAccumulator {
  private final StringBuilder pending = new StringBuilder();
  private int bytes;
  private boolean dropping;
  public synchronized List<String> accept(String chunk) {
    if (chunk == null || chunk.isEmpty()) return Collections.emptyList();
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < chunk.length(); i++) {
      char c = chunk.charAt(i);
      if (c == '\n') {
        if (!dropping && bytes + 1 <= 128 && pending.length() > 0) lines.add(pending.toString());
        clear();
      } else {
        bytes = Math.min(128, bytes + 1);
        if (bytes > 127 || c > 127 || c < 32 && c != '\r') {
          dropping = true; pending.setLength(0);
        }
        if (!dropping && c != '\r') pending.append(c);
      }
    }
    return lines;
  }
  public synchronized void clear() { pending.setLength(0); bytes = 0; dropping = false; }
}

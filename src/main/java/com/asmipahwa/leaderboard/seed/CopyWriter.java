package com.asmipahwa.leaderboard.seed;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;

/**
 * Streams CSV rows into Postgres with {@code COPY ... FROM STDIN}.
 *
 * <p>COPY loads hundreds of thousands of rows per second, versus a few thousand with batched
 * INSERTs, which is what makes a 500k-event seed take seconds instead of minutes. Rows are buffered
 * and flushed in ~1 MB chunks so memory stays flat.
 */
final class CopyWriter implements AutoCloseable {

  private static final int FLUSH_BYTES = 1 << 20;

  private final CopyIn copyIn;
  private final StringBuilder buffer = new StringBuilder(FLUSH_BYTES + 1024);
  private long rows;

  CopyWriter(Connection connection, String table, String columns) throws SQLException {
    String sql = "COPY " + table + " (" + columns + ") FROM STDIN WITH (FORMAT csv)";
    this.copyIn = connection.unwrap(PGConnection.class).getCopyAPI().copyIn(sql);
  }

  /** Appends one row. Values must not contain commas, quotes or newlines (ours never do). */
  void row(Object... values) throws SQLException {
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        buffer.append(',');
      }
      buffer.append(values[i] == null ? "" : values[i]);
    }
    buffer.append('\n');
    rows++;
    if (buffer.length() >= FLUSH_BYTES) {
      flush();
    }
  }

  long rows() {
    return rows;
  }

  private void flush() throws SQLException {
    byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
    copyIn.writeToCopy(bytes, 0, bytes.length);
    buffer.setLength(0);
  }

  @Override
  public void close() throws SQLException {
    flush();
    copyIn.endCopy();
  }
}

package com.yamikhal.e4hytale.relay;

import java.util.concurrent.atomic.AtomicLong;

// counters the relay loop writes and the status page reads
final class Metrics {

    final AtomicLong hosts = new AtomicLong();
    final AtomicLong peers = new AtomicLong();
    final AtomicLong names = new AtomicLong();
    final AtomicLong joins = new AtomicLong();
    // joiners turned away: unknown name, full host, rate limit
    final AtomicLong refused = new AtomicLong();
    // host datagrams larger than the payload limit
    final AtomicLong dropped = new AtomicLong();
    final AtomicLong bytesIn = new AtomicLong();
    final AtomicLong bytesOut = new AtomicLong();

    // Prometheus text format, also readable by eye
    String render() {
        return "e4h_hosts " + this.hosts.get() + "\n"
                + "e4h_peers " + this.peers.get() + "\n"
                + "e4h_names " + this.names.get() + "\n"
                + "e4h_joins_total " + this.joins.get() + "\n"
                + "e4h_refused_total " + this.refused.get() + "\n"
                + "e4h_dropped_total " + this.dropped.get() + "\n"
                + "e4h_bytes_in_total " + this.bytesIn.get() + "\n"
                + "e4h_bytes_out_total " + this.bytesOut.get() + "\n";
    }
}

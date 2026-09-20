package com.example.highrps.shared;

import io.hypersistence.tsid.TSID;

/**
 * Generates TSIDs using a node ID derived from environment variables.
 * Suitable for Kubernetes StatefulSets using pod-index or multi-instance Docker Compose.
 */
public class IdGenerator {
    private IdGenerator() {}

    private static final TSID.Factory FACTORY;

    static {
        int nodeId = 0;
        int nodeCount = 1024; // Default max nodes for 10-bit node id

        try {
            String tsidNode = System.getenv("TSID_NODE");
            if (tsidNode != null && !tsidNode.isEmpty()) {
                nodeId = Integer.parseInt(tsidNode);
            }

            String tsidNodeCount = System.getenv("TSID_NODE_COUNT");
            if (tsidNodeCount != null && !tsidNodeCount.isEmpty()) {
                nodeCount = Integer.parseInt(tsidNodeCount);
            }
        } catch (NumberFormatException e) {
            // Fallback to default
        }

        // Configure the TSID factory
        FACTORY = TSID.Factory.builder()
                .withNodeBits(Integer.SIZE - Integer.numberOfLeadingZeros(nodeCount - 1))
                .withNode(nodeId)
                .build();
    }

    public static String generateString() {
        return FACTORY.generate().toString();
    }

    public static Long generateLong() {
        return FACTORY.generate().toLong();
    }
}

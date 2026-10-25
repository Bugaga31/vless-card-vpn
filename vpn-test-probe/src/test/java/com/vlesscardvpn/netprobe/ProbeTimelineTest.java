package com.vlesscardvpn.netprobe;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProbeTimelineTest {
    @Test public void correlatesRequestWithoutPrintingDestination() {
        assertEquals("probe run=3 request=0 event=begin phase=url wall_ms=100 elapsed_ms=20",
            ProbeTimeline.event(3,0,"begin","url",100,20));
    }
    @Test public void retainsFailedStageAndCompletion() {
        assertTrue(ProbeTimeline.event(3,0,"error","connect_headers",200,120).contains("event=error phase=connect_headers"));
        assertTrue(ProbeTimeline.event(3,1,"complete","body",200,120).contains("request=1 event=complete phase=body"));
    }
    @Test public void rejectsPrivateOrInvalidFields() {
        assertEquals("probe run=0 request=99 event=other phase=other wall_ms=0 elapsed_ms=0",
            ProbeTimeline.event(-1,1000,"SECRET","https://private.example",-1,-1));
    }
}

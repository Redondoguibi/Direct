package com.redondoguibi.direct;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FrameTimeWindowTest {
    @Test void reportsSpikesRatherThanOnlyAverage(){
        var window=new FrameTimeWindow(100);for(int i=0;i<99;i++)window.add(10_000_000);window.add(200_000_000);
        var s=window.summary();assertEquals(10,s.p95Ms());assertEquals(200,s.maxMs());assertEquals(11.9,s.meanMs(),.0001);assertEquals(1000/11.9,s.fps(),.0001);
    }
    @Test void boundedRecentPercentilesKeepWholeWindowMeanAndMax(){
        var window=new FrameTimeWindow(2);window.add(100_000_000);window.add(10_000_000);window.add(20_000_000);
        var s=window.summary();assertEquals(3,s.frames());assertEquals(2,s.samples());assertEquals(20,s.p99Ms());assertEquals(100,s.maxMs());
        window.clear();assertEquals(0,window.summary().frames());assertEquals(0,window.summary().fps());
    }
}

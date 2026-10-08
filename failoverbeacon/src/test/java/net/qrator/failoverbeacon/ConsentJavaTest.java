package net.qrator.failoverbeacon;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public final class ConsentJavaTest {
    @Test
    public void everyMethodIsCallableFromJava() {
        assertEquals(Consent.PENDING, Consent.valueOf("PENDING"));
        assertEquals(
                Arrays.asList(Consent.GRANTED, Consent.PENDING, Consent.NOT_GRANTED),
                Consent.getEntries());
    }
}

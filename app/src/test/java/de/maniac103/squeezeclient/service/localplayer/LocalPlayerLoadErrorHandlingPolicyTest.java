package de.maniac103.squeezeclient.service.localplayer;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo;
import java.io.FileNotFoundException;
import java.io.IOException;
import org.junit.Test;

public class LocalPlayerLoadErrorHandlingPolicyTest {
    private final LocalPlayerLoadErrorHandlingPolicy policy = new LocalPlayerLoadErrorHandlingPolicy();

    @Test
    public void networkFailuresKeepRetryingAfterTheDefaultLimit() {
        int[] reasons = {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        };
        for (int reason : reasons) {
            IOException error = new HttpDataSourceException(null, reason, HttpDataSourceException.TYPE_READ);
            assertEquals(5000L, policy.getRetryDelayMsFor(info(error, 20, C.DATA_TYPE_MEDIA)));
        }
        assertEquals(Integer.MAX_VALUE, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA));
    }

    @Test
    public void badHttpResponsesKeepTheDefaultRetryLimit() {
        IOException error = new HttpDataSourceException(
            null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, HttpDataSourceException.TYPE_OPEN);
        DefaultLoadErrorHandlingPolicy original = new DefaultLoadErrorHandlingPolicy();
        LoadErrorInfo third = info(error, 3, C.DATA_TYPE_MEDIA);
        assertEquals(original.getRetryDelayMsFor(third), policy.getRetryDelayMsFor(third));
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(error, 4, C.DATA_TYPE_MEDIA)));
    }

    @Test
    public void otherIoErrorsKeepTheDefaultRetryLimitForTheirDataType() {
        IOException error = new IOException();
        assertEquals(2000L, policy.getRetryDelayMsFor(info(error, 3, C.DATA_TYPE_MEDIA)));
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(error, 4, C.DATA_TYPE_MEDIA)));
        policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE);
        assertEquals(5000L, policy.getRetryDelayMsFor(info(error, 6, C.DATA_TYPE_MEDIA)));
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(error, 7, C.DATA_TYPE_MEDIA)));
    }

    @Test
    public void corruptMediaAndMissingFilesRemainFatal() {
        IOException parserError = ParserException.createForMalformedContainer("invalid stream", null);
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(parserError, 1, C.DATA_TYPE_MEDIA)));
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(new FileNotFoundException(), 1, C.DATA_TYPE_MEDIA)));
    }

    // Request and event fields are unused by this policy.
    private LoadErrorInfo info(IOException error, int count, int type) {
        return new LoadErrorInfo(null, new MediaLoadData(type), error, count);
    }
}

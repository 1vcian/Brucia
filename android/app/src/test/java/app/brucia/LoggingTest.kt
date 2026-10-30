package app.brucia

import org.junit.Test

class LoggingTest {
    @Test fun unavailableAndroidLogSinkDoesNotThrow() {
        BruciaLog.d("test", "synthetic")
        BruciaLog.i("test", "synthetic")
        BruciaLog.w("test", "synthetic", IllegalArgumentException("fixture"))
        BruciaLog.e("test", "synthetic", IllegalStateException("fixture"))
    }
}

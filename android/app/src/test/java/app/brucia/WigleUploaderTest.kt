package app.brucia

import app.brucia.wardrive.WigleUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WigleUploaderTest {

    @Test fun successWithTopLevelTransid() {
        val r = WigleUploader.parseResponse(200, """{"success":true,"transid":"20231010-00001"}""")
        assertTrue(r is WigleUploader.Result.Success)
        assertEquals("20231010-00001", (r as WigleUploader.Result.Success).transid)
    }

    @Test fun successWithResultsArrayTransid() {
        val r = WigleUploader.parseResponse(
            200,
            """{"success":true,"results":[{"transid":"20231010-00002","filename":"x.csv"}]}""",
        )
        assertTrue(r is WigleUploader.Result.Success)
        assertEquals("20231010-00002", (r as WigleUploader.Result.Success).transid)
    }

    @Test fun failureSurfacesMessage() {
        val r = WigleUploader.parseResponse(401, """{"success":false,"message":"too many requests"}""")
        assertTrue(r is WigleUploader.Result.Failure)
        assertEquals("too many requests", (r as WigleUploader.Result.Failure).message)
    }

    @Test fun nonJsonBodyOnErrorCode() {
        val r = WigleUploader.parseResponse(500, "<html>oops</html>")
        assertTrue(r is WigleUploader.Result.Failure)
    }
}

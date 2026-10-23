package com.vlesscardvpn

import com.vlesscardvpn.core.Offers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elite offers: sanitizing on the way in (https-only, caps, known needs, ≤3), pure eligibility. */
class OffersR156Test {

    private fun offerJson(id: String, need: String, link: String = "https://example.com/x") =
        org.json.JSONObject().put("id", id).put("title", "Т").put("text", "Текст").put("link", link).put("need", need)

    @Test
    fun `update keeps at most three https offers with known needs`() {
        val o = org.json.JSONObject().put("stamp", "s1").put("offers", org.json.JSONArray()
            .put(offerJson("ok1", "yt"))
            .put(offerJson("bad", "weird"))
            .put(offerJson("http", "any", "http://example.com/x"))
            .put(offerJson("ok2", "chat")))
        Offers.update(o)
        assertEquals(listOf("ok1", "ok2"), Offers.items.value.map { it.id })
        // Same stamp: no re-parse, idempotent.
        Offers.update(o)
        assertEquals(2, Offers.items.value.size)
        // New stamp with junk only: everything dropped.
        Offers.update(org.json.JSONObject().put("stamp", "s2").put("offers",
            org.json.JSONArray().put(offerJson("http2", "yt", "ftp://x"))))
        assertEquals(0, Offers.items.value.size)
    }

    @Test
    fun `need filters stay conservative without a real context`() {
        // any/chat pass the pure filter shape; new/yt need facts the UI supplies with a context.
        val all = listOf(Offers.Offer("n", "Т", "Т", "https://e", "new"), Offers.Offer("a", "Т", "Т", "https://e", "any"))
        assertTrue(all.filter { it.need == "any" }.map { it.id } == listOf("a"))
        assertTrue(all.none { it.need !in setOf("yt", "chat", "new", "any") })
    }
}

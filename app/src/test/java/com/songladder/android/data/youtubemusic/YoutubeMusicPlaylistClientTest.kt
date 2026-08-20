package com.songladder.android.data.youtubemusic

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeMusicPlaylistClientTest {
    private val client = YoutubeMusicPlaylistClient(OkHttpClient())

    @Test
    fun `parses public playlist html into preview`() {
        val preview = client.parsePlaylistHtml(
            url = "https://music.youtube.com/playlist?list=PL123",
            html = """
                <html>
                <head>
                  <meta property="og:title" content="Night Drive" />
                </head>
                <body>
                <script>
                  var ytInitialData = {
                    "contents": [{
                      "videoId": "abc123",
                      "title": { "runs": [{ "text": "Midnight City" }] },
                      "subtitle": { "runs": [{ "text": "M83" }, { "text": "Hurry Up, We're Dreaming" }] },
                      "thumbnail": { "thumbnails": [{ "url": "https://img/1.jpg" }, { "url": "https://img/2.jpg" }] }
                    }, {
                      "videoId": "def456",
                      "title": { "runs": [{ "text": "Genesis" }] },
                      "subtitle": { "runs": [{ "text": "" }] }
                    }]
                  };
                </script>
                </body>
                </html>
            """.trimIndent()
        )

        assertEquals("Night Drive", preview.playlistTitle)
        assertEquals(1, preview.importableTracks.size)
        assertEquals("Midnight City", preview.importableTracks.single().title)
        assertEquals("M83", preview.importableTracks.single().artist)
        assertEquals(1, preview.ambiguousTracks.size)
    }

    @Test
    fun `parses live initialData array format into preview`() {
        val preview = client.parsePlaylistHtml(
            url = "https://music.youtube.com/playlist?list=PL123",
            html = """
                <html>
                <head>
                  <meta property="og:title" content="Night Drive" />
                </head>
                <body>
                <script>
                  try {
                    const initialData = [];
                    initialData.push({path: '\/browse', params: JSON.parse('\x7b\x7d'), data: '\x7b\x22contents\x22:\x5b\x7b\x22musicResponsiveListItemRenderer\x22:\x7b\x22playlistItemData\x22:\x7b\x22videoId\x22:\x22abc123\x22\x7d,\x22flexColumns\x22:\x5b\x7b\x22musicResponsiveListItemFlexColumnRenderer\x22:\x7b\x22text\x22:\x7b\x22runs\x22:\x5b\x7b\x22text\x22:\x22Midnight City\x22\x7d\x5d\x7d\x7d\x7d,\x7b\x22musicResponsiveListItemFlexColumnRenderer\x22:\x7b\x22text\x22:\x7b\x22runs\x22:\x5b\x7b\x22text\x22:\x22M83\x22\x7d,\x7b\x22text\x22:\x22 \u2022 \x22\x7d,\x7b\x22text\x22:\x22Hurry Up, We\\u0027re Dreaming\x22\x7d\x5d\x7d\x7d\x7d\x5d,\x22thumbnail\x22:\x7b\x22musicThumbnailRenderer\x22:\x7b\x22thumbnail\x22:\x7b\x22thumbnails\x22:\x5b\x7b\x22url\x22:\x22https:\/\/img\/1.jpg\x22\x7d,\x7b\x22url\x22:\x22https:\/\/img\/2.jpg\x22\x7d\x5d\x7d\x7d\x7d\x7d\x7d,\x7b\x22musicResponsiveListItemRenderer\x22:\x7b\x22playlistItemData\x22:\x7b\x22videoId\x22:\x22def456\x22\x7d,\x22flexColumns\x22:\x5b\x7b\x22musicResponsiveListItemFlexColumnRenderer\x22:\x7b\x22text\x22:\x7b\x22runs\x22:\x5b\x7b\x22text\x22:\x22Genesis\x22\x7d\x5d\x7d\x7d\x7d\x5d\x7d\x7d\x5d\x7d'});
                    ytcfg.set({'YTMUSIC_INITIAL_DATA': initialData});
                  } catch (e) {}
                </script>
                </body>
                </html>
            """.trimIndent()
        )

        assertEquals("Night Drive", preview.playlistTitle)
        assertEquals(1, preview.importableTracks.size)
        assertEquals("Midnight City", preview.importableTracks.single().title)
        assertEquals("M83", preview.importableTracks.single().artist)
        assertEquals("Hurry Up, We're Dreaming", preview.importableTracks.single().album)
        assertTrue(preview.unsupportedCount >= 0)
    }

    @Test
    fun `parses browse api payload into preview`() {
        val preview = client.parseBrowseResponse(
            playlistId = "PL123",
            playlistTitle = "Night Drive",
            body = """
                {
                  "contents": [{
                    "musicResponsiveListItemRenderer": {
                      "playlistItemData": { "videoId": "abc123" },
                      "flexColumns": [
                        {
                          "musicResponsiveListItemFlexColumnRenderer": {
                            "text": { "runs": [{ "text": "Midnight City" }] }
                          }
                        },
                        {
                          "musicResponsiveListItemFlexColumnRenderer": {
                            "text": {
                              "runs": [
                                { "text": "M83" },
                                { "text": " • " },
                                { "text": "Hurry Up, We're Dreaming" }
                              ]
                            }
                          }
                        }
                      ],
                      "thumbnail": {
                        "musicThumbnailRenderer": {
                          "thumbnail": {
                            "thumbnails": [
                              { "url": "https://img/1.jpg" },
                              { "url": "https://img/2.jpg" }
                            ]
                          }
                        }
                      }
                    }
                  }]
                }
            """.trimIndent()
        )

        assertEquals("Night Drive", preview.playlistTitle)
        assertEquals(1, preview.importableTracks.size)
        assertEquals("Midnight City", preview.importableTracks.single().title)
        assertEquals("M83", preview.importableTracks.single().artist)
        assertEquals("Hurry Up, We're Dreaming", preview.importableTracks.single().album)
        assertEquals("abc123", preview.importableTracks.single().externalId)
    }

    @Test
    fun `resolves the real video id nested under playlistItemData, not a synthetic hash`() {
        val preview = client.parseBrowseResponse(
            playlistId = "PL123",
            playlistTitle = "Night Drive",
            body = """
                {
                  "contents": [
                    {
                      "musicResponsiveListItemRenderer": {
                        "playlistItemData": { "videoId": "abc123" },
                        "flexColumns": [
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": { "runs": [{ "text": "Midnight City" }] }
                            }
                          },
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": { "runs": [{ "text": "M83" }] }
                            }
                          }
                        ]
                      }
                    },
                    {
                      "musicResponsiveListItemRenderer": {
                        "playlistItemData": { "videoId": "def456" },
                        "flexColumns": [
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": { "runs": [{ "text": "Genesis" }] }
                            }
                          },
                          {
                            "musicResponsiveListItemFlexColumnRenderer": {
                              "text": { "runs": [{ "text": "Justice" }] }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals(2, preview.importableTracks.size)
        val externalIds = preview.importableTracks.map { it.externalId }.toSet()
        assertTrue("abc123" in externalIds)
        assertTrue("def456" in externalIds)
    }

    @Test
    fun `ignores menu action text and overlay navigation fragments when parsing a row`() {
        val preview = client.parseBrowseResponse(
            playlistId = "PL123",
            playlistTitle = "Night Drive",
            body = """
                {
                  "contents": [{
                    "musicResponsiveListItemRenderer": {
                      "playlistItemData": { "videoId": "abc123" },
                      "flexColumns": [
                        {
                          "musicResponsiveListItemFlexColumnRenderer": {
                            "text": { "runs": [{ "text": "Midnight City" }] }
                          }
                        },
                        {
                          "musicResponsiveListItemFlexColumnRenderer": {
                            "text": {
                              "runs": [
                                { "text": "M83" },
                                { "text": " • " },
                                { "text": "Hurry Up, We're Dreaming" }
                              ]
                            }
                          }
                        }
                      ],
                      "overlay": {
                        "musicItemThumbnailOverlayRenderer": {
                          "content": {
                            "musicPlayButtonRenderer": {
                              "playNavigationEndpoint": {
                                "watchEndpoint": { "videoId": "abc123" }
                              }
                            }
                          }
                        }
                      },
                      "menu": {
                        "menuRenderer": {
                          "items": [
                            { "menuNavigationItemRenderer": { "text": { "runs": [{ "text": "Add to queue" }] } } },
                            { "menuNavigationItemRenderer": { "text": { "runs": [{ "text": "Start radio" }] } } },
                            { "menuNavigationItemRenderer": { "text": { "runs": [{ "text": "Save to playlist" }] } } }
                          ]
                        }
                      }
                    }
                  }]
                }
            """.trimIndent()
        )

        assertEquals(1, preview.importableTracks.size)
        val track = preview.importableTracks.single()
        assertEquals("abc123", track.externalId)
        assertEquals("Midnight City", track.title)
        assertEquals("M83", track.artist)
        assertEquals("Hurry Up, We're Dreaming", track.album)
    }

    @Test
    fun `unsupported browser page fails clearly`() {
        try {
            client.parsePlaylistHtml(
                url = "https://music.youtube.com/playlist?list=PL123",
                html = """
                    <html>
                    <head><title>Your browser is no longer supported</title></head>
                    <body>YouTube Music is not optimized for your browser.</body>
                    </html>
                """.trimIndent()
            )
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("unsupported browser"))
            return
        }
        error("Expected unsupported browser failure")
    }

    @Test
    fun `invalid html fails clearly`() {
        try {
            client.parsePlaylistHtml(
                url = "https://music.youtube.com/playlist?list=PL123",
                html = "<html><body>No data</body></html>"
            )
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("Could not read this playlist"))
            return
        }
        error("Expected parse failure")
    }
}

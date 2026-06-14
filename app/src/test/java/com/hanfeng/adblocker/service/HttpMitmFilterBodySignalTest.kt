package com.HanFeng.service

import com.HanFeng.data.RuleRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class HttpMitmFilterBodySignalTest {
    @Test
    fun `reader card ad fields produce reader ad reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "reader_bottom_card": {"ad_position":"bottom", "ad_scene":"reader"},
              "material_url":"https://example.com/ad.jpg",
              "landing_url":"https://example.com/landing"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 3)
        assertTrue(inspection.reasons.any { it.contains("reader") || it.contains("novel-field") })
    }

    @Test
    fun `coolapk comment ad fields produce comment ad reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "comment_ad_card": {"ad_material":"x", "material_url":"https://example.com/ad.png"},
              "comment": {"id":"1"},
              "click_url":"https://example.com/click",
              "track_url":"https://example.com/track"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 3)
        assertTrue(inspection.reasons.any { it.startsWith("comment-") || it.contains("ad-field") })
    }

    @Test
    fun `coolapk sponsored comment entity fields produce comment ad reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "data": [
                {
                  "entityType": "sponsor",
                  "entityTemplate": "comment_card",
                  "sponsorInfo": {"title":"brand"},
                  "adExtra": {"adSource":"gdt", "ecpm": 120},
                  "material_url":"https://example.com/native.jpg",
                  "landing_url":"https://example.com/landing",
                  "comment": {"id":"100"}
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 3)
        assertTrue(inspection.reasons.any { it.contains("comment") || it.contains("general-ad-field") })
    }

    @Test
    fun `qimao reader visible pangle banner text produces reader ad reason`() {
        val inspection = inspectBodySignals(
            """
            <div class="reader-ad-card">
              <span>穿山甲广告</span>
              <button>查看详情</button>
              <a>免广告</a>
            </div>
            """.trimIndent()
        )

        assertTrue(inspection.score >= 3)
        assertTrue(inspection.reasons.contains("reader-visible-ad-text"))
    }

    @Test
    fun `qimao reader interstitial visible text produces reader ad reason`() {
        val inspection = inspectBodySignals(
            """
            <section>
              <p>广告是为了更好地支持作者创作</p>
              <span>看视频免广告</span>
              <button>戳我下载</button>
              <footer>滑动可继续阅读 ></footer>
            </section>
            """.trimIndent()
        )

        assertTrue(inspection.score >= 3)
        assertTrue(inspection.reasons.contains("reader-visible-ad-text"))
    }

    @Test
    fun `escaped json ad fields are decoded before inspection`() {
        val inspection = inspectBodySignals(
            "{\"payload\":\"{\\\"\\u0061\\u0064_\\u0064\\u0061\\u0074\\u0061\\\":{\\\"material_url\\\":\\\"https://example.com/ad.png\\\",\\\"click_url\\\":\\\"https://example.com/click\\\"}}\"}"
        )

        assertTrue(inspection.score >= 3)
        assertTrue(inspection.reasons.isNotEmpty())
    }

    @Test
    fun `jsonp wrapped ad payload is inspected`() {
        val inspection = inspectBodySignals(
            "callback123({\"startup_ad\":{\"material_url\":\"https://example.com/splash.jpg\",\"landing_url\":\"https://example.com/landing\"}});"
        )

        assertTrue(inspection.score >= 3)
        assertTrue(inspection.reasons.any { it.contains("startup") || it.contains("ad-field") })
    }

    @Test
    fun `mediation auction payload produces high confidence ad reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "waterfall_id": "wf-1",
              "adn_name": "gromore",
              "network_placement_id": "slot-123",
              "bid_payload": "opaque-token",
              "auction_id": "auc-1",
              "creative_url": "https://cdn.example.com/creative.html",
              "impression_urls": ["https://track.example.com/imp"],
              "click_urls": ["https://track.example.com/click"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.any { it.contains("mediation") || it.contains("adn-placement") })
    }

    @Test
    fun `sdk config payload produces high confidence ad reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "mediation": "topon",
              "waterfall": [{"adn_name":"pangle"}],
              "ad_unit_id": "unit-1001",
              "sdk_slot_id": "slot-2002",
              "native_template_id": "tpl-native-feed",
              "rewarded_video_ad": true,
              "material_url": "https://cdn.example.com/native.html",
              "show_trackers": ["https://track.example.com/show"],
              "click_trackers": ["https://track.example.com/click"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("sdk-config-ad-extended"))
        assertTrue(inspection.reasons.contains("sdk-tracker-array-extended"))
    }

    @Test
    fun `ad markup payload produces high confidence ad reason`() {
        val inspection = inspectBodySignals(
            """
            {
              "waterfall_id": "wf-markup",
              "ad_unit_id": "reward-slot-1",
              "bid_response": {"price": 12},
              "adm": "<VAST version='3.0'><Ad><InLine></InLine></Ad></VAST>",
              "mraid": true,
              "omid": {"vendor":"iab"},
              "impression_urls": ["https://track.example.com/imp"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("ad-markup-payload-extended"))
    }

    @Test
    fun `attribution payload produces high confidence ad reason`() {
        val inspection = inspectBodySignals(
            """
            {
              "mediation": "max",
              "placement_id": "interstitial-main",
              "auction_id": "auc-42",
              "skadn": {
                "campaign_id": "88",
                "source_app_id": "123456789",
                "conversion_value": 7,
                "attribution_signature": "signed-token"
              },
              "click_trackers": ["https://track.example.com/click"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("attribution-payload-extended"))
    }

    @Test
    fun `generic mobile ad sdk payload produces tracker array reason`() {
        val inspection = inspectBodySignals(
            """
            {
              "ad_unit_id": "native-001",
              "auction": true,
              "bid_id": "bid-9",
              "ecpm_floor": 18,
              "creative_data": {"render_data":"template-v2", "asset_list":["image", "video"]},
              "tracking_list": ["https://track.example.com/imp"],
              "view_trackers": ["https://track.example.com/view"],
              "click_trackers": ["https://track.example.com/click"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("sdk-tracker-array-extended"))
    }

    @Test
    fun `openrtb bid response payload produces high confidence ad reason`() {
        val inspection = inspectBodySignals(
            """
            {
              "id": "auction-100",
              "seatbid": [
                {
                  "bid": [
                    {
                      "impid": "slot-1",
                      "price": 3.2,
                      "adomain": ["advertiser.example"],
                      "cid": "campaign-7",
                      "crid": "creative-9",
                      "adm": "<VAST version='3.0'><Ad></Ad></VAST>",
                      "nurl": "https://track.example.com/win",
                      "burl": "https://track.example.com/bill",
                      "lurl": "https://track.example.com/loss",
                      "iurl": "https://cdn.example.com/creative.png"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("openrtb-bid-response-extended"))
    }

    @Test
    fun `admob mediation payload produces high confidence ad reason`() {
        val inspection = inspectBodySignals(
            """
            {
              "admob": true,
              "response_id": "resp-1",
              "ad_unit_id": "ca-app-pub-xxx/rewarded",
              "mediation_adapter_class_name": "com.google.ads.mediation.pangle.PangleAdapter",
              "adapter_responses": [{"network_name":"pangle", "latency_ms":120}],
              "auction_id": "auc-1",
              "creative_url": "https://cdn.example.com/reward.html",
              "impression_urls": ["https://track.example.com/imp"],
              "click_urls": ["https://track.example.com/click"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("admob-mediation-payload-extended"))
    }

    @Test
    fun `vast video payload produces high confidence ad reason`() {
        val inspection = inspectBodySignals(
            """
            <VAST version="4.2">
              <Ad id="pre-roll-1">
                <InLine>
                  <Impression><![CDATA[https://track.example.com/imp]]></Impression>
                  <Creatives><Creative><Linear><MediaFiles><MediaFile>https://cdn.example.com/ad.mp4</MediaFile></MediaFiles></Linear></Creative></Creatives>
                  <VideoClicks><ClickThrough>https://landing.example.com</ClickThrough></VideoClicks>
                </InLine>
              </Ad>
            </VAST>
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("vast-video-ad-payload-extended"))
    }

    @Test
    fun `native assets and playable endcard payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "placement_id": "native-feed-1",
              "native_ad": {
                "assets": [
                  {"id":1, "title":{"text":"promo"}},
                  {"id":2, "img":{"url":"https://cdn.example.com/native.jpg"}},
                  {"id":3, "video":{"vasttag":"<VAST></VAST>"}}
                ],
                "link": {"url":"https://landing.example.com"},
                "imptrackers": ["https://track.example.com/imp"],
                "eventtrackers": [{"url":"https://track.example.com/view"}],
                "playable_url": "https://cdn.example.com/playable.html",
                "endcard_url": "https://cdn.example.com/endcard.html",
                "click_url": "https://track.example.com/click"
              }
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("native-assets-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("playable-endcard-payload-extended"))
    }

    @Test
    fun `push notification and fake system alert ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "push_type": "promotion",
              "notification": {"title":"系统更新", "content":"手机内存不足，立即清理"},
              "system_alert": true,
              "operation_popup": {"campaign":"cleaner-ad"},
              "ad": true,
              "material_url": "https://cdn.example.com/cleaner.png",
              "deeplink": "market://details?id=com.example.cleaner",
              "click_url": "https://track.example.com/click"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("push-notification-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("fake-system-alert-ad-payload-extended"))
    }

    @Test
    fun `deeplink market and operation popup ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "activity_popup": true,
              "operation_banner": {"promotion":"new-user"},
              "campaign": "install-campaign-1",
              "creative_id": "creative-100",
              "package": "com.example.promoted",
              "market_url": "market://details?id=com.example.promoted",
              "intent_url": "intent://open#Intent;scheme=promo;end",
              "landing_url": "https://landing.example.com/app",
              "track_url": "https://track.example.com/show"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("deeplink-market-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("operation-popup-ad-payload-extended"))
    }

    @Test
    fun `precache material and shake sensor ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "shake_ad": true,
              "motion_trigger": "accelerometer",
              "ad": {"scene":"splash"},
              "preload_material": ["https://cdn.example.com/splash.mp4"],
              "cache_material": {"image_url":"https://cdn.example.com/banner.jpg"},
              "prefetch_url": "https://cdn.example.com/endcard.html",
              "shake_landing": "market://details?id=com.example.game",
              "impression_urls": ["https://track.example.com/imp"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("precache-material-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("shake-sensor-ad-payload-extended"))
    }

    @Test
    fun `httpdns and websocket ad delivery payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "httpdns": true,
              "dns_records": [{"qname":"ads.example.com", "cname":"gdt.example.com", "ips":["203.0.113.10"], "ttl":60}],
              "websocket": {"wss_url":"wss://push.example.com/ad-stream", "stream_channel":"promotion"},
              "ad": true,
              "material_url": "https://cdn.example.com/banner.jpg",
              "tracking_urls": ["https://track.example.com/show"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("httpdns-ad-resolution-payload-extended"))
        assertTrue(inspection.reasons.contains("websocket-sse-ad-stream-payload-extended"))
    }

    @Test
    fun `grpc protobuf and encrypted config ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "grpc": "application/grpc",
              "protobuf": true,
              "adservice": "mobile.AdService/GetAds",
              "ad_response_pb": "base64-payload",
              "placement_id": "reward-slot-8",
              "encrypted_config": true,
              "encrypted_payload": "ciphertext",
              "cipher_text": "opaque",
              "config_sign": "signed",
              "nonce": "nonce-1",
              "waterfall": [{"adn_name":"pangle"}],
              "bid_token": "bid-token"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("grpc-protobuf-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("encrypted-config-ad-payload-extended"))
    }

    @Test
    fun `dynamic code and private gateway ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "dynamic_module": "adsdk",
              "ad_plugin": true,
              "dex_url": "https://cdn.example.com/adsdk.dex",
              "plugin_url": "https://cdn.example.com/ad-plugin.zip",
              "ad_gateway": "tcp://gateway.example.com:9000",
              "adsdk_gateway": "quic://gateway.example.com:443",
              "gateway_token": "token",
              "ad": true,
              "campaign": "install-campaign",
              "track_url": "https://track.example.com/report"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("dynamic-code-ad-module-payload-extended"))
        assertTrue(inspection.reasons.contains("private-protocol-ad-gateway-payload-extended"))
    }

    @Test
    fun `media preroll and audio ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "player": "vod",
              "video": {"episode":"ep1"},
              "ad_breaks": [{"type":"preroll", "cue_points":[0]}],
              "vast_url": "https://ads.example.com/vast.xml",
              "ad_tag_url": "https://ads.example.com/tag",
              "skip_offset": "00:00:05",
              "impression_urls": ["https://track.example.com/imp"],
              "audio": true,
              "audio_preroll": {"ad_audio_url":"https://cdn.example.com/ad.mp3"},
              "listen_ad": {"click_url":"https://landing.example.com/audio"}
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("media-preroll-metadata-payload-extended"))
        assertTrue(inspection.reasons.contains("audio-ad-break-payload-extended"))
    }

    @Test
    fun `comic and short drama unlock ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "comic": {"chapter_id":"100"},
              "manga_unlock_ad": true,
              "comic_reward_ad": {"material_url":"https://cdn.example.com/comic-ad.jpg"},
              "short_drama": {"episode":"12"},
              "episode_unlock_ad": true,
              "drama_reward_ad": {"video_url":"https://cdn.example.com/drama-ad.mp4"},
              "landing_url": "https://landing.example.com/open",
              "track_url": "https://track.example.com/show"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("comic-manga-unlock-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("short-drama-episode-ad-payload-extended"))
    }

    @Test
    fun `generic ad array with materials and trackers produces high confidence reason`() {
        val inspection = inspectBodySignals(
            """
            {
              "ad_list": [
                {
                  "template_id": "native-feed-v2",
                  "creative_data": {"title":"promo"},
                  "material_url": "https://cdn.example.com/ad.jpg",
                  "landing_url": "https://landing.example.com/open",
                  "impression_urls": ["https://track.example.com/imp"],
                  "click_trackers": ["https://track.example.com/click"]
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("generic-ad-array-material"))
        assertTrue(inspection.reasons.contains("generic-ad-template-tracker"))
    }

    @Test
    fun `task reward and game ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "task_ad": {"watch_ad_reward": true, "coin_reward_ad": 30},
              "offerwall": {"placement_id":"offer-1", "material_url":"https://cdn.example.com/task.jpg"},
              "game_interstitial": {"level_complete_ad": true, "revive_ad": true},
              "game_reward_video": {"video_url":"https://cdn.example.com/revive.mp4"},
              "impression_urls": ["https://track.example.com/show"],
              "click_url": "https://landing.example.com/open"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("task-reward-offerwall-payload-extended"))
        assertTrue(inspection.reasons.contains("game-interstitial-ad-payload-extended"))
    }

    @Test
    fun `live room and search recommendation ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "live_room_ad": {"anchor_ad": true, "live_banner":"top"},
              "search_result_ad": {"hotword_ad":"brand", "keyword_ad":"phone"},
              "recommend_ad": {"suggestion_ad": true},
              "slot_id": "search-live-slot",
              "creative_url": "https://cdn.example.com/card.html",
              "track_url": "https://track.example.com/track"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("live-room-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("search-recommend-ad-payload-extended"))
    }

    @Test
    fun `experiment config and miniapp landing ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "ab_test": {"exp_id":"ad-exp-1", "ad_switch": true, "show_ad": true},
              "remote_config": {"ad_strategy":"gray"},
              "mini_program": {"wx_appid":"wx123", "wechat_path":"/pages/ad/landing"},
              "h5_landing": {"landing_page":"https://landing.example.com/h5"},
              "ad_info": {"ad_unit_id":"miniapp-slot"},
              "monitor_urls": ["https://track.example.com/monitor"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("experiment-ad-config-payload-extended"))
        assertTrue(inspection.reasons.contains("miniapp-landing-ad-payload-extended"))
    }

    @Test
    fun `fake buttons clipboard service worker and system surface ads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "ad_data": {"ad_unit_id":"surface-slot"},
              "fake_close": true,
              "download_button": {"click_area":"full", "misclick":"high"},
              "clipboard": {"copy_text":"复制口令打开应用", "share_reward": true},
              "service_worker": {"sw_url":"https://cdn.example.com/ad-sw.js", "precache_manifest":"ads"},
              "widget_ad": {"launcher_badge":"promo", "lockscreen_ad": true},
              "landing_url":"https://landing.example.com/open",
              "monitor_urls":["https://track.example.com/monitor"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("fake-action-button-payload-extended"))
        assertTrue(inspection.reasons.contains("clipboard-share-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("serviceworker-cache-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("system-surface-ad-payload-extended"))
    }

    @Test
    fun `social cloud asset and coupon ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "ad_info": {"ad_unit_id":"social-cloud-slot"},
              "comment_ad": {"comment_card_ad": true, "follow_ad": true, "profile_ad": true},
              "cloud_template": {"template_ad": true, "template_url":"https://cdn.example.com/ad.tpl", "strategy_id":"ad-strategy"},
              "resource_pack": {"asset_pack":"creative", "bundle_url":"https://cdn.example.com/ad.zip", "hot_patch":"ad-module"},
              "redpacket_ad": {"coupon_ad": true, "cashback_ad": true, "bonus_ad": true},
              "material_url":"https://cdn.example.com/material.jpg",
              "track_urls":["https://track.example.com/show"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("social-interaction-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("cloud-template-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("asset-package-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("coupon-redpacket-ad-payload-extended"))
    }

    @Test
    fun `commerce local life leadgen and calendar ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "ad_data": {"slot_id":"commerce-local-slot"},
              "product_ad": {"affiliate_ad": true, "commission_ad": true, "coupon_landing":"https://landing.example.com/coupon"},
              "local_life_ad": {"nearby_ad": true, "poi_ad": true, "weather_ad": true, "tool_ad": true},
              "leadgen_ad": {"lead_form_ad": true, "survey_ad": true, "lead_url":"https://lead.example.com/form"},
              "calendar_reminder_ad": {"calendar_subscribe_ad": true, "ics_url":"https://cdn.example.com/ad.ics"},
              "material_url":"https://cdn.example.com/native.jpg",
              "monitor_urls":["https://track.example.com/monitor"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("ecommerce-affiliate-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("local-life-tool-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("leadgen-survey-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("calendar-reminder-ad-payload-extended"))
    }

    @Test
    fun `browser appstore oem and cross device ad payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "ad_info": {"ad_unit_id":"device-slot"},
              "browser_ad": {"startpage_ad": true, "newtab_ad": true, "search_suggestion_ad": true},
              "appstore_ad": {"promoted_app": true, "app_install_ad": true, "apk_ad": true},
              "oem_ad": {"system_manager_ad": true, "security_ad": true, "storage_clean_ad": true},
              "tv_ad": {"ott_ad": true, "cast_ad": true, "wear_ad": true, "car_ad": true},
              "creative_url":"https://cdn.example.com/device.html",
              "tracking_urls":["https://track.example.com/event"]
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("browser-startpage-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("appstore-promotion-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("oem-security-cleaner-ad-payload-extended"))
        assertTrue(inspection.reasons.contains("cross-device-ad-payload-extended"))
    }

    @Test
    fun `lightweight payload model scores ad-like json and hidden html structures`() {
        val inspection = inspectBodySignals(
            """
            {
              "reward": true,
              "reward_amount": 30,
              "duration": 15,
              "video_url":"https://cdn.example.com/v.mp4",
              "landing_url":"https://landing.example.com/open",
              "click_tracking_urls":["https://track.example.com/click"],
              "impression_urls":["https://track.example.com/show"],
              "ad_unit_id":"reward-unit-1001"
            }
            <iframe width="1" height="1" style="display:none" src="https://track.example.com/pixel"></iframe>
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("lightweight-payload-model-ad-extended"))
    }

    @Test
    fun `wasm loader and media fingerprint payloads produce high confidence reasons`() {
        val inspection = inspectBodySignals(
            """
            {
              "ad_data": {"placement_id":"wasm-media-slot"},
              "wasm_url":"https://cdn.example.com/adloader.wasm",
              "wasm_hash":"wasm-ad-hash",
              "obfuscated_js":"eval(function(p,a,c,k,e,d){})",
              "loader_signature":"packed-ad-loader",
              "phash":"ffeeddccbbaa9988",
              "perceptual_hash":"aa55aa55aa55aa55",
              "watermark_text":"sponsored",
              "video_fingerprint":"endcard-watermark",
              "creative_url":"https://cdn.example.com/creative.html",
              "track_url":"https://track.example.com/show"
            }
            """.trimIndent()
        )

        assertTrue(inspection.score >= 4)
        assertTrue(inspection.reasons.contains("wasm-js-obfuscated-loader-ad-extended"))
        assertTrue(inspection.reasons.contains("media-fingerprint-watermark-ad-extended"))
    }

    @Test
    fun `html injection includes rule scriptlets`() {
        val body = "<html><head><title>x</title></head><body><div>ok</div></body></html>"
        val injected = injectHtml(
            body = body,
            selectors = listOf(".sponsored-card"),
            csp = null,
            scripts = setOf("window.__hanfeng_test = true;")
        )

        assertTrue(injected.contains(".sponsored-card"))
        assertTrue(injected.contains("window.__hanfeng_test = true;"))
        assertTrue(injected.contains("data-hanfeng-rule-inject"))
    }

    @Test
    fun `html injection blocks h5 behavior ad channels`() {
        val injected = injectHtml(
            body = "<html><head></head><body><div>ok</div></body></html>",
            selectors = emptyList(),
            csp = null,
            scripts = emptySet()
        )

        assertTrue(injected.contains("window.WebSocket"))
        assertTrue(injected.contains("window.EventSource"))
        assertTrue(injected.contains("Notification.requestPermission"))
        assertTrue(injected.contains("devicemotion"))
        assertTrue(injected.contains("market"))
        assertTrue(injected.contains("intent"))
        assertTrue(injected.contains("window.open"))
        assertTrue(injected.contains("clipboard.writeText"))
        assertTrue(injected.contains("navigator.share"))
        assertTrue(injected.contains("serviceWorker.register"))
        assertTrue(injected.contains("adBridge"))
        assertTrue(injected.contains("navigator.getBiddingToken"))
        assertTrue(injected.contains("navigator.connectAd"))
        assertTrue(injected.contains("DeviceMotionEvent"))
        assertTrue(injected.contains("touchmove"))
        assertTrue(injected.contains("querySelectorAll('iframe,script,img,video,source,link')"))
        assertTrue(injected.contains("ad_unit_id"))
        assertTrue(injected.contains("WebAssembly.instantiate"))
        assertTrue(injected.contains("WebAssembly.compileStreaming"))
    }

    @Test
    fun `strong ad query parameters make path inspection strong suspicious`() {
        val inspection = inspectPath("/api/config?slot_id=reward_slot_1001&auction_id=auction-abcdef12")

        assertTrue(inspection.strongSuspicious)
        assertTrue(inspection.suspicious)
    }

    @Test
    fun `httpdns ad query makes path inspection strong suspicious`() {
        val inspection = inspectPath("/httpdns/resolve?host=adservice.example.com&type=1")

        assertTrue(inspection.strongSuspicious)
        assertTrue(inspection.suspicious)
    }

    @Test
    fun `generalized ad query parameters make path inspection strong suspicious`() {
        val inspection = inspectPath("/api/feed?track_token=YWQtYXVjdGlvbi0xMjM0&placement_url=https%3A%2F%2Fad.example.com%2Flanding")

        assertTrue(inspection.strongSuspicious)
        assertTrue(inspection.suspicious)
    }

    @Test
    fun `http3 quic gateway query makes path inspection strong suspicious`() {
        val inspection = inspectPath("/net/route?quic_gateway=adedge.example.com&udp443=1&alt_svc=h3")

        assertTrue(inspection.strongSuspicious)
        assertTrue(inspection.suspicious)
    }

    @Test
    fun `html scrub removes ad sdk script tags`() {
        val body = "<html><head><script src=\"https://example.com/pangle-ad-sdk.js\"></script></head><body><p>content</p></body></html>"
        val scrubbed = scrubHtml(body)

        assertFalse(scrubbed.contains("pangle-ad-sdk.js"))
        assertTrue(scrubbed.contains("content"))
    }

    @Test
    fun `html scrub removes meta refresh noscript and ad images`() {
        val body = """
            <html><head>
            <meta http-equiv="refresh" content="0;url=https://adservice.example.com/landing">
            </head><body>
            <noscript><img src="https://doubleclick.example.com/banner.gif"></noscript>
            <img src="https://gdt.example.com/ad-banner.png">
            <p>content</p>
            </body></html>
        """.trimIndent()
        val scrubbed = scrubHtml(body)

        assertFalse(scrubbed.contains("http-equiv=\"refresh\""))
        assertFalse(scrubbed.contains("doubleclick.example.com"))
        assertFalse(scrubbed.contains("gdt.example.com"))
        assertTrue(scrubbed.contains("content"))
    }

    @Test
    fun `html scrub removes ad containers templates and dynamic loader scripts`() {
        val body = """
            <html><head>
            <script>var s=document.createElement('script');s.src='https://cdn.example.com/pangle/sdk.js';document.head.appendChild(s);</script>
            </head><body>
            <div class="content-card">keep</div>
            <section class="native-ad sponsor-card"><a href="https://ad.example.com">ad</a></section>
            <template id="feed-ad-template"><div>ad template</div></template>
            <ins class="adsbygoogle" data-ad-slot="123"></ins>
            </body></html>
        """.trimIndent()
        val scrubbed = scrubHtml(body)

        assertTrue(scrubbed.contains("content-card"))
        assertFalse(scrubbed.contains("pangle/sdk.js"))
        assertFalse(scrubbed.contains("native-ad"))
        assertFalse(scrubbed.contains("feed-ad-template"))
        assertFalse(scrubbed.contains("adsbygoogle"))
    }

    @Test
    fun `redirect resources include json html and vast placeholders`() {
        val jsonBody = redirectBody("application/json", "noop-json")
        val htmlBody = redirectBody("text/html", "blank-html")
        val vastBody = redirectBody("application/xml", "noop-vast")

        assertEquals("{}", String(jsonBody, StandardCharsets.UTF_8))
        assertTrue(String(htmlBody, StandardCharsets.UTF_8).contains("<html>"))
        assertTrue(String(vastBody, StandardCharsets.UTF_8).contains("<VAST"))
        assertEquals("application/json; charset=utf-8", redirectContentType("text/plain", "noop-json"))
        assertEquals("application/xml; charset=utf-8", redirectContentType("text/plain", "noop-vast"))
    }

    @Test
    fun `http2 complete html body applies cosmetic and script injection`() {
        val rewrite = HttpMitmFilter.rewriteHttp2CompleteTextBody(
            contentType = "text/html; charset=utf-8",
            body = "<html><head></head><body><div class=\"ad-card\">ad</div><main>content</main></body></html>",
            directives = RuleRepository.RequestRewriteDirectives(
                cosmeticSelectors = listOf(".ad-card"),
                jsInjectRules = setOf("window.__hanfeng_http2 = true;")
            )
        )

        val body = String(rewrite!!.body, StandardCharsets.UTF_8)
        assertEquals("cosmetic-html-injected", rewrite.reason)
        assertTrue(body.contains(".ad-card"))
        assertTrue(body.contains("window.__hanfeng_http2 = true;"))
        assertTrue(body.contains("content"))
    }

    @Test
    fun `http2 complete text body applies replace rules`() {
        val encodedReplace = "ad_payload\u0000blocked_payload\u0000g"
        val rewrite = HttpMitmFilter.rewriteHttp2CompleteTextBody(
            contentType = "application/json; charset=utf-8",
            body = "{\"slot\":\"ad_payload\"}",
            directives = RuleRepository.RequestRewriteDirectives(replaceRules = setOf(encodedReplace))
        )

        assertEquals("replace-rule-applied", rewrite!!.reason)
        assertEquals("{\"slot\":\"blocked_payload\"}", String(rewrite.body, StandardCharsets.UTF_8))
    }

    private fun inspectBodySignals(body: String): BodySignalView {
        val method = HttpMitmFilter::class.java.getDeclaredMethod("inspectAdBodySignals", String::class.java)
        method.isAccessible = true
        val result = method.invoke(HttpMitmFilter, body.lowercase())
        val scoreField = result.javaClass.getDeclaredField("score")
        val reasonsField = result.javaClass.getDeclaredField("reasons")
        scoreField.isAccessible = true
        reasonsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return BodySignalView(
            score = scoreField.getInt(result),
            reasons = reasonsField.get(result) as List<String>
        )
    }

    private data class BodySignalView(
        val score: Int,
        val reasons: List<String>
    )

    private fun inspectPath(path: String): PathInspectionView {
        val method = HttpMitmFilter::class.java.getDeclaredMethod("inspectSuspiciousHttpPath", String::class.java)
        method.isAccessible = true
        val result = method.invoke(HttpMitmFilter, path)
        val suspiciousField = result.javaClass.getDeclaredField("suspicious")
        val strongSuspiciousField = result.javaClass.getDeclaredField("strongSuspicious")
        val rewardUnlockField = result.javaClass.getDeclaredField("rewardUnlock")
        suspiciousField.isAccessible = true
        strongSuspiciousField.isAccessible = true
        rewardUnlockField.isAccessible = true
        return PathInspectionView(
            suspicious = suspiciousField.getBoolean(result),
            strongSuspicious = strongSuspiciousField.getBoolean(result),
            rewardUnlock = rewardUnlockField.getBoolean(result)
        )
    }

    private data class PathInspectionView(
        val suspicious: Boolean,
        val strongSuspicious: Boolean,
        val rewardUnlock: Boolean
    )

    private fun injectHtml(body: String, selectors: List<String>, csp: String?, scripts: Set<String>): String {
        val method = HttpMitmFilter::class.java.getDeclaredMethod(
            "buildInjectedHtmlBody",
            String::class.java,
            List::class.java,
            String::class.java,
            Set::class.java
        )
        method.isAccessible = true
        val bytes = method.invoke(HttpMitmFilter, body, selectors, csp, scripts) as ByteArray
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun scrubHtml(body: String): String {
        val method = HttpMitmFilter::class.java.getDeclaredMethod(
            "scrubHtmlAdArtifacts",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(HttpMitmFilter, "text/html; charset=utf-8", body) as String
    }

    private fun redirectBody(contentType: String, resource: String): ByteArray {
        val method = HttpMitmFilter::class.java.getDeclaredMethod(
            "buildRedirectReplacementBody",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(HttpMitmFilter, contentType, resource) as ByteArray
    }

    private fun redirectContentType(contentType: String, resource: String): String {
        val method = HttpMitmFilter::class.java.getDeclaredMethod(
            "inferRedirectContentType",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(HttpMitmFilter, contentType, resource) as String
    }
}

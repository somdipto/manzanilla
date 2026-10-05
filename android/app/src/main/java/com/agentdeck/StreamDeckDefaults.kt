package com.agentdeck

import org.json.JSONArray

/**
 * Built-in Stream Deck pages used before the PC bridge connects and whenever a
 * bridge sends an empty page list.  Keeping these in the launcher makes the
 * physical controller understandable and usable-looking in every theme.
 */
object StreamDeckDefaults {
    val json = """
        [
          {
            "name":"APPS",
            "keys":{
              "1":{"label":"WhatsApp","type":"app","value":"whatsapp:","match":"whatsapp"},
              "2":{"label":"Files","type":"app","value":"explorer.exe","match":"file explorer"},
              "3":{"label":"Chrome","type":"app","value":"chrome","match":"chrome"},
              "4":{"label":"Claude","type":"app","value":"claude:","match":"claude"},
              "5":{"label":"Notion","type":"app","value":"notion:","match":"notion"},
              "6":{"label":"Screenshot","type":"screenshot","value":""},
              "7":{"label":"Spotify","type":"app","value":"spotify:","match":"spotify"},
              "8":{"label":"Comet","type":"app","value":"comet","match":"comet"},
              "9":{"label":"ChatGPT","type":"app","value":"chatgpt","match":"chatgpt"}
            }
          },
          {
            "name":"AI",
            "keys":{
              "1":{"label":"Claude App","type":"app","value":"claude:","match":"claude"},
              "2":{"label":"ChatGPT","type":"app","value":"chatgpt","match":"chatgpt"},
              "3":{"label":"Wispr","type":"app","value":"wispr","match":"wispr"},
              "4":{"label":"Copy Last","type":"hotkey","value":"ctrl+c"},
              "5":{"label":"Paste","type":"hotkey","value":"ctrl+v"},
              "6":{"label":"Codex","type":"app","value":"codex","match":"codex"},
              "7":{"label":"Comet","type":"app","value":"comet","match":"comet"},
              "8":{"label":"Stop AI","type":"hotkey","value":"esc"},
              "9":{"label":"New Task","type":"hotkey","value":"ctrl+n"}
            }
          },
          {
            "name":"MEDIA",
            "keys":{
              "1":{"label":"Play/Pause","type":"media","value":"play"},
              "2":{"label":"Next","type":"media","value":"next"},
              "3":{"label":"Previous","type":"media","value":"prev"},
              "4":{"label":"Volume +","type":"media","value":"volup"},
              "5":{"label":"Volume -","type":"media","value":"voldown"},
              "6":{"label":"Mute","type":"media","value":"mute"},
              "7":{"label":"Spotify","type":"app","value":"spotify:","match":"spotify"},
              "8":{"label":"Lock PC","type":"hotkey","value":"win+l"},
              "9":{"label":"Desktop","type":"hotkey","value":"win+d"}
            }
          },
          {
            "name":"CREATIVE",
            "keys":{
              "1":{"label":"Creative Cloud","type":"app","value":"C:/Program Files/Adobe/Adobe Creative Cloud/ACC/Creative Cloud.exe","match":"creative cloud"}
            }
          }
        ]
    """.trimIndent()

    fun pages(raw: String): JSONArray = try {
        JSONArray(raw).takeIf { it.length() > 0 } ?: JSONArray(json)
    } catch (_: Exception) {
        JSONArray(json)
    }

    fun pageCount(raw: String): Int = pages(raw).length().coerceAtLeast(1)
}

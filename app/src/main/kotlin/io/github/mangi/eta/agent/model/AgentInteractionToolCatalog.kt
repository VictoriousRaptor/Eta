package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentInteractionToolCatalog {
    const val REQUEST_USER_INPUT = "request_user_input"

    fun appendTo(tools: JSONArray) {
        tools.put(AgentToolSchema.function(
            REQUEST_USER_INPUT,
            "缺少只有用户能提供且影响执行结果的关键信息时，提出 1 到 3 个短问题并等待回答，然后继续当前任务。可给出最多 4 个简短选项，用户也可自由输入。能够用现有工具查到的信息先自行查询。",
            JSONObject("""{
              "type":"object","additionalProperties":false,"required":["questions"],
              "properties":{"questions":{"type":"array","minItems":1,"maxItems":3,"items":{
                "type":"object","additionalProperties":false,"required":["id","question"],
                "properties":{
                  "id":{"type":"string","pattern":"^[a-zA-Z0-9_-]{1,64}$"},
                  "question":{"type":"string","minLength":1,"maxLength":500},
                  "options":{"type":"array","maxItems":4,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":120}}
                }
              }}}
            }"""),
        ))
    }
}

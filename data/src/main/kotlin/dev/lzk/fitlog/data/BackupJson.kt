package dev.lzk.fitlog.data

import org.json.JSONObject

/** Bound parser recursion before org.json sees a user-supplied backup. */
internal fun parseBackupObject(text:String):JSONObject {
    require(text.toByteArray(Charsets.UTF_8).size<=JsonBackupRepository.MAX_BYTES) {"备份记录文件最大 32 MB"}
    var depth=0;var quoted=false;var escaped=false
    text.forEach {c->
        if(quoted) {
            if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false
        } else when(c) {
            '"' -> quoted=true
            '{','[' -> {depth++;require(depth<=32) {"备份结构嵌套过深"}}
            '}',']' -> depth--
        }
    }
    return JSONObject(text)
}

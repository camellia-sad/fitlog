package dev.lzk.fitlog.data

import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.*
import java.time.Instant
import java.util.zip.*
import java.security.MessageDigest

/** Portable files contain no login tokens, SMTP credentials or account ownership switches. */
class PortableBackupRepository(private val core:JsonBackupRepository,private val social:SupabaseSocialRepository) {
    companion object {const val MAX_ARCHIVE=50*1024*1024;const val MAX_EXPANDED=64*1024*1024}
    data class Preview(val createdAt:Instant,val includesChat:Boolean,val messageCount:Int,val mediaCount:Int,
        internal val coreJson:String,internal val owner:String?,internal val chat:String?,internal val media:Map<String,ByteArray>,internal val posts:JSONObject?)
    suspend fun export(includeChat:Boolean):ByteArray=withContext(Dispatchers.IO) {
        social.localGate.withLock {
            val owner=social.profile.value?.id
            require(!includeChat || owner!=null) {"备份聊天需要先登录对应账号"}
            val files=linkedMapOf("fitlog.json" to core.exportJson().toByteArray())
            if(includeChat) {
                val chat=social.localChat.export(requireNotNull(owner))
                files["chat/messages.json"]=chat.toByteArray()
                val paths=chatPaths(chat)
                for(path in paths) {
                    val bytes=social.localChat.image(owner,path) ?: error("聊天图片尚未完整保存，请联网打开聊天后再备份")
                    files[mediaName(path)]=bytes
                }
            }
            if(owner!=null) {
                val posts=social.exportOwnPosts(owner)
                files["social/posts.json"]=posts.toString().toByteArray()
                for(path in postPaths(posts)) {
                    files[mediaName(path)]=social.localChat.image(owner,path) ?: error("动态图片未完整保存，请联网完成云端备份")
                }
            }
            require(files.values.sumOf {it.size.toLong()}<=MAX_EXPANDED) {"备份内容超过 64 MB"}
            val manifest=JSONObject().put("format","FitLogArchive").put("version",1).put("createdAt",Instant.now().toString())
                .put("owner",owner).put("includesChat",includeChat)
            val hashes=JSONObject()
            files.forEach {(name,bytes)->hashes.put(name,sha256(bytes))}
            manifest.put("files",hashes)
            files.filterKeys {!it.startsWith("media/")}.values.forEach {require(it.size<=JsonBackupRepository.MAX_BYTES) {"单个记录文件超过 32 MB"}}
            val output=ByteArrayOutputStream()
            ZipOutputStream(output).use {zip->
                for((name,bytes) in files+("manifest.json" to manifest.toString().toByteArray())) {
                    zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
                    require(output.size()<=MAX_ARCHIVE) {"完整备份超过 50 MB，请减少聊天图片后重试"}
                }
            }
            output.toByteArray().also {require(it.size<=MAX_ARCHIVE) {"完整备份超过 50 MB"}}
        }
    }
    suspend fun preview(bytes:ByteArray):Preview=withContext(Dispatchers.IO) {
        require(bytes.size<=MAX_ARCHIVE) {"备份最大 50 MB"}
        val files=linkedMapOf<String,ByteArray>()
        var expanded=0
        ZipInputStream(ByteArrayInputStream(bytes)).use {zip->
            while(true) {
                val entry=zip.nextEntry ?: break
                val name=entry.name
                require(!entry.isDirectory && (name in listOf("manifest.json","fitlog.json","chat/messages.json","social/posts.json") ||
                    name.matches(Regex("media/[a-f0-9]{64}\\.jpg"))) && name !in files && files.size<20000) {"备份文件路径或结构无效"}
                val limit=if(name.startsWith("media/")) 2_097_152 else JsonBackupRepository.MAX_BYTES
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {
                    val count=zip.read(buffer);if(count<0) break
                    expanded+=count
                    require(output.size()+count<=limit && expanded<=MAX_EXPANDED) {"备份解压后过大"}
                    output.write(buffer,0,count)
                }
                files[name]=output.toByteArray();zip.closeEntry()
            }
        }
        val manifest=JSONObject(String(files["manifest.json"] ?: error("备份缺少清单"),Charsets.UTF_8))
        require(manifest.getString("format")=="FitLogArchive" && manifest.getInt("version")==1) {"不支持的完整备份版本"}
        val hashes=manifest.getJSONObject("files")
        require(hashes.keys().asSequence().toSet()==files.keys-"manifest.json") {"备份清单不完整"}
        files.filterKeys {it!="manifest.json"}.forEach {(name,data)->require(hashes.getString(name)==sha256(data)) {"备份文件损坏"}}
        val owner=manifest.optional("owner")
        val include=manifest.getBoolean("includesChat")
        val chat=files["chat/messages.json"]?.toString(Charsets.UTF_8)
        require(include==(chat!=null)) {"聊天备份内容缺失"}
        val media=linkedMapOf<String,ByteArray>()
        val posts=files["social/posts.json"]?.let {JSONObject(it.toString(Charsets.UTF_8))}
        if(posts!=null) {
            val account=social.profile.value?.id ?: error("恢复动态图片需要先登录")
            require(owner==account) {"备份不属于当前账号"}
            social.validateOwnPosts(account,posts)
            for(path in postPaths(posts)) media[path]=files[mediaName(path)] ?: error("备份缺少动态图片")
        }
        if(chat!=null) {
            val account=social.profile.value?.id ?: error("恢复聊天需要先登录")
            require(owner==account) {"聊天备份不属于当前账号"}
            val (rows,pending)=social.localChat.validate(account,chat)
            social.localChat.checkConflicts(account,rows,pending)
            for(path in chatPaths(chat)) media[path]=files[mediaName(path)] ?: error("备份缺少聊天图片")
        }
        require(files.keys.filter {it.startsWith("media/")}.toSet()==media.keys.map {mediaName(it)}.toSet()) {"图片清单无效"}
        val coreJson=files["fitlog.json"]?.toString(Charsets.UTF_8) ?: error("缺少训练备份")
        core.validateJson(coreJson)
        Preview(Instant.parse(manifest.getString("createdAt")),include,
            chat?.let {JSONObject(it).getJSONArray("messages").objects().count {!it.getBoolean("hidden")}} ?: 0,
            media.size,coreJson,owner,chat,media,posts)
    }
    suspend fun restore(preview:Preview)=withContext(Dispatchers.IO) {
        social.localGate.withLock {
            if(preview.posts!=null) {
                check(social.profile.value?.id==preview.owner) {"账号已切换"}
                social.validateOwnPosts(requireNotNull(preview.owner),preview.posts)
            }
            if(preview.chat!=null) {
                check(social.profile.value?.id==preview.owner) {"账号已切换"}
                val owner=requireNotNull(preview.owner)
                val (rows,pending)=social.localChat.validate(owner,preview.chat)
                social.localChat.checkConflicts(owner,rows,pending)
                // Stage all images before changing databases; disk failure cannot lose cloud copies.
                preview.media.forEach {(path,data)->social.localChat.saveImage(owner,path,data)}
                core.validateJson(preview.coreJson)
                core.importJson(preview.coreJson)
                social.localChat.restore(owner,rows,pending)
            } else {
                preview.owner?.let {owner->preview.media.forEach {(path,data)->social.localChat.saveImage(owner,path,data)}}
                core.importJson(preview.coreJson)
            }
            preview.posts?.let {social.restoreOwnPosts(requireNotNull(preview.owner),social.validateOwnPosts(preview.owner,it))}
            preview.owner?.let {social.localChat.pruneImages(it,preview.media.keys)}
        }
        Unit
    }
    suspend fun exportLocalJson(includeChat:Boolean=true):String=withContext(Dispatchers.IO) {
        social.localGate.withLock {
            val root=JSONObject(core.exportJson())
            val owner=social.profile.value?.id
            if(owner!=null) {
                root.put("publishedPosts",social.exportOwnPosts(owner))
                if(includeChat) root.put("chat",JSONObject(social.localChat.export(owner)))
            }
            root.toString().also {require(it.toByteArray().size<=JsonBackupRepository.MAX_BYTES) {"本地 JSON 备份最大 32 MB"}}
        }
    }
    suspend fun importLocalJson(text:String):ImportResult=withContext(Dispatchers.IO) {
        social.localGate.withLock {
            require(text.toByteArray().size<=JsonBackupRepository.MAX_BYTES) {"本地 JSON 备份最大 32 MB"}
            val root=JSONObject(text)
            val chat=root.optJSONObject("chat")?.toString()
            val posts=root.optJSONObject("publishedPosts")
            val owner=social.profile.value?.id
            val parsed=chat?.let {
                val account=owner ?: error("导入聊天需要先登录对应账号")
                social.localChat.validate(account,it).also {(rows,pending)->social.localChat.checkConflicts(account,rows,pending)}
            }
            val ownPosts=posts?.let {social.validateOwnPosts(owner ?: error("导入动态记录需要先登录对应账号"),it)}
            core.validateJson(text)
            val result=core.importJson(text)
            if(parsed!=null) social.localChat.restore(requireNotNull(owner),parsed.first,parsed.second)
            if(ownPosts!=null) social.restoreOwnPosts(requireNotNull(owner),ownPosts)
            result
        }
    }
    private fun postPaths(posts:JSONObject)=posts.getJSONArray("posts").objects()
        .filter {!it.getBoolean("hidden")}.flatMap {it.getJSONObject("post").getJSONArray("media_paths").strings()}.toSet()
    private fun chatPaths(text:String):Set<String> {
        val root=JSONObject(text)
        return buildSet {
            root.getJSONArray("messages").objects().filter {!it.getBoolean("hidden")}.forEach {
                addAll(it.getJSONObject("message").chatMessage().mediaPaths())
            }
            root.getJSONArray("pending").objects().forEach {j->
                val draft=j.getJSONObject("draft")
                draft.optional("media_path")?.let {add(it)}
                (draft.optJSONArray("forwarded") ?: org.json.JSONArray()).objects().mapNotNull {it.optional("media_path")}.forEach {add(it)}
            }
        }.also {paths->paths.forEach {requireMediaPath(it)}}
    }
    private fun mediaName(path:String)="media/"+sha256(path.toByteArray())+".jpg"
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
}

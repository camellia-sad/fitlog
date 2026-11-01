package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class DataRemoval(val label:String,val details:String) {
    TRAINING("清空训练记录","删除本机全部训练历史和当前训练，停止训练计时；保留模板、计划、自定义动作、收藏、偏好和聊天。云端已共享训练及备份不受影响。"),
    CHAT("清空聊天记录","删除当前账号在本机的全部聊天内容、待发送消息及对应图片，保留好友、动态与动态图片。对方的记录、云端待送达消息和旧备份不受影响。"),
    ACCOUNT("注销账号","需联网执行。永久删除云端账号、好友关系、动态、评论、点赞、共享训练、图片和云端备份，清除本机该账号的聊天与社交图片。保留本机训练历史、模板、计划、自定义动作和偏好；可用原邮箱注册新账号。已导出的文件及他人设备上的副本无法一并删除。")
}

@Composable
internal fun DataManagementButtons(loggedIn:Boolean,busy:Boolean,onTraining:()->Unit,onChats:()->Unit,onAccount:()->Unit) {
    var selected by remember{mutableStateOf<DataRemoval?>(null)}
    var confirmation by remember{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        DataRemoval.entries.forEach {action->
            OutlinedButton(onClick={selected=action;confirmation=""},
                enabled=!busy && (action==DataRemoval.TRAINING || loggedIn),
                modifier=Modifier.fillMaxWidth(),
                colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text(action.label)}
        }
        if(!loggedIn)Text("登录后可清空当前账号的聊天或注销账号。",style=MaterialTheme.typography.bodySmall)
        if(busy)Text("正在处理，请稍候。",style=MaterialTheme.typography.bodySmall)
    }
    selected?.let {action->
        AlertDialog(onDismissRequest={if(!busy)selected=null},title={Text(action.label+"？")},
            text={Column {
                Text(action.details+" 此操作无法撤销。")
                if(action==DataRemoval.ACCOUNT)OutlinedTextField(confirmation,{confirmation=it},
                    label={Text("输入“注销”确认")},singleLine=true)
            }},
            confirmButton={TextButton(enabled=!busy && (action!=DataRemoval.ACCOUNT || confirmation=="注销"),onClick={
                selected=null
                when(action) {DataRemoval.TRAINING->onTraining();DataRemoval.CHAT->onChats();DataRemoval.ACCOUNT->onAccount()}
            }){Text("确认"+action.label)}},
            dismissButton={TextButton(enabled=!busy,onClick={selected=null}){Text("取消")}})
    }
}

package dev.lzk.fitlog

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataManagementFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun offlineTrainingClearRequiresConfirmationAndDoesNotEnableSocialRemoval() {
        var training=0;var chat=0;var account=0
        compose.setContent {FitLogTheme {DataManagementButtons(false,false,{training++},{chat++},{account++})}}
        compose.onNodeWithText("清空聊天记录").assertIsNotEnabled()
        compose.onNodeWithText("注销账号").assertIsNotEnabled()
        compose.onNodeWithText("清空训练记录").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0,training)
        compose.onNodeWithText("清空训练记录").performClick()
        compose.onNodeWithText("确认清空训练记录").performClick()
        assertEquals(1,training);assertEquals(0,chat);assertEquals(0,account)
    }
    @Test fun chatAndAccountHaveSeparateScopesAndAccountNeedsTypedConfirmation() {
        var training=0;var chat=0;var account=0
        compose.setContent {FitLogTheme {DataManagementButtons(true,false,{training++},{chat++},{account++})}}
        compose.onNodeWithText("清空聊天记录").performClick()
        compose.onNodeWithText("确认清空聊天记录").performClick()
        assertEquals(1,chat);assertEquals(0,training)
        compose.onNodeWithText("注销账号").performClick()
        compose.onNodeWithText("确认注销账号").assertIsNotEnabled()
        compose.onNodeWithText("输入“注销”确认").performTextInput("确认")
        compose.onNodeWithText("确认注销账号").assertIsNotEnabled()
        compose.onNodeWithText("输入“注销”确认").performTextReplacement("注销")
        compose.onNodeWithText("确认注销账号").performClick()
        assertEquals(1,account);assertEquals(1,chat);assertEquals(0,training)
    }
}

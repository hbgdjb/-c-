package com.redpanda.cppide.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 打开浏览器 */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** 调起邮件客户端发送邮件（ACTION_SENDTO + mailto:，无需任何权限） */
private fun openEmail(context: android.content.Context, email: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
        )
    }
}

/**
 * 「关于」对话框：按开源规范写明原作来源、许可与第三方组件。
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于", fontSize = 17.sp) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("小熊猫C++ 安卓版", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("版本 1.0  ·  包名 com.redpanda.cppide", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "一个内置完整 C/C++ 编译器的安卓原生 IDE，完全离线可用。",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )

                SectionTitle("原作来源")

                Text(
                    "本项目参考并复刻自桌面版「小熊猫C++」（RedPanda-C++），" +
                        "一个跨平台的轻量级 C/C++ 集成开发环境。",
                    fontSize = 12.sp
                )
                InfoLine("原作作者", "Roy Qu")
                LinkLine("原作仓库", "github.com/royqh1979/RedPanda-CPP") {
                    openUrl(context, "https://github.com/royqh1979/RedPanda-CPP")
                }
                LinkLine("原作官网", "royqh.net/redpandacpp") {
                    openUrl(context, "http://royqh.net/redpandacpp")
                }
                InfoLine("原作许可", "GPL-3.0")
                Text(
                    "本项目的代码模板素材直接复刻自原作；因复用了 GPLv3 素材，本项目同样以 GPL-3.0 发布。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                SectionTitle("本项目")

                LinkLine("源码仓库", "github.com/hbgdjb/-c-") {
                    openUrl(context, "https://github.com/hbgdjb/-c-")
                }
                InfoLine("本项目许可", "GPL-3.0")
                Text(
                    "完整源代码（含构建脚本与内置工具链）均已在上述仓库公开，欢迎提交 Issue 与 PR。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                SectionTitle("第三方组件")

                DepLine(
                    name = "Sora Editor",
                    license = "LGPL-2.1",
                    use = "代码编辑器与语法高亮",
                    url = "https://github.com/Rosemoe/sora-editor"
                )
                DepLine(
                    name = "termux-ndk r29",
                    license = "Android SDK 许可 / Apache-2.0",
                    use = "内置的 aarch64 clang / lld / lldb / clangd 工具链",
                    url = "https://github.com/lzhiyong/termux-ndk"
                )
                DepLine(
                    name = "Android NDK (r29)",
                    license = "Apache-2.0",
                    use = "交叉编译工具链来源",
                    url = "https://developer.android.com/ndk"
                )
                DepLine(
                    name = "Jetpack Compose / AndroidX",
                    license = "Apache-2.0",
                    use = "界面框架",
                    url = "https://developer.android.com/jetpack/androidx"
                )
                DepLine(
                    name = "commons-compress / XZ for Java",
                    license = "Apache-2.0",
                    use = "首启解压内置工具链",
                    url = "https://commons.apache.org/proper/commons-compress/"
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                SectionTitle("联系方式")

                LinkLine("邮箱", "2931621472@qq.com") {
                    openEmail(context, "2931621472@qq.com")
                }
                LinkLine("源码仓库", "github.com/hbgdjb/-c-") {
                    openUrl(context, "https://github.com/hbgdjb/-c-")
                }
                Text(
                    "若你认为本项目侵犯了你的权益，或有任何问题与建议，" +
                        "欢迎通过上方的邮箱或仓库 Issue 联系，我会及时处理。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun SectionTitle(t: String) {
    Text(
        t,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LinkLine(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Text(
            value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable { onClick() }
        )
        Text("  ↗", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DepLine(name: String, license: String, use: String, url: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(140.dp))
        Text(license, fontSize = 10.sp, modifier = Modifier.width(110.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            "仓库",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { openUrl(context, url) }
        )
    }
    Text(
        use,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}

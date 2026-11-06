package com.yangfei.xingmu.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.yangfei.xingmu.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yangfei.xingmu.core.theme.XColors

/** 应用内统一的品牌标识，适配手机标题和电视导航。 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 36.dp, showName: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.mipmap.ic_launcher), contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.25f)))
        if (showName) {
            Spacer(Modifier.width(10.dp))
            Column {
                Text("飞哥影视仓", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = XColors.TextPrimary, maxLines = 1)
                Text("FEIGE CINEMA", style = MaterialTheme.typography.bodySmall, color = Color(0xFF21DBD0), maxLines = 1)
            }
        }
    }
}

package com.techno.kurdtube.frontend.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.techno.kurdtube.R


@ExperimentalMaterial3Api
@Composable
fun TopBarComponent(onClickTitleText: () -> Unit) {

    CenterAlignedTopAppBar(
        title = {
            ClickableAppName(onClick = onClickTitleText)
        },
        modifier = Modifier.shadow(4.dp)
    )

}

@Composable
fun ClickableAppName(onClick: () -> Unit) {
    //the app logo sits next to the app name, from the right side next to the K
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = stringResource(id = R.string.app_name),
            modifier = Modifier.size(32.dp)
        )

        Text(
            text = stringResource(id = R.string.app_name),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .padding(2.dp)
        )
    }
}

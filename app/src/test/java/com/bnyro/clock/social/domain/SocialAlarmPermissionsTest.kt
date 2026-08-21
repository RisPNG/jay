package com.bnyro.clock.social.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialAlarmPermissionsTest {
    @Test
    fun leadersCanEditLeaderOnlyAlarms() {
        assertTrue(
            SocialGroup(
                "group", "Group", AlarmPermission.LEADERS,
                false, false, false, false, false, false, MemberRole.LEADER
            ).canEditAlarms
        )
    }

    @Test
    fun membersCannotEditLeaderOnlyAlarms() {
        assertFalse(
            SocialGroup(
                "group", "Group", AlarmPermission.LEADERS,
                false, false, false, false, false, false, MemberRole.MEMBER
            ).canEditAlarms
        )
    }

    @Test
    fun everyMemberCanEditEveryoneAlarms() {
        assertTrue(
            SocialGroup(
                "group", "Group", AlarmPermission.EVERYONE,
                false, false, false, false, false, false, MemberRole.MEMBER
            ).canEditAlarms
        )
    }
}

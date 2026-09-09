import django.contrib.postgres.fields
import django.db.models.deletion
import django.utils.timezone
import uuid
from django.db import migrations, models


class Migration(migrations.Migration):

    initial = True

    dependencies = [
    ]

    operations = [
        migrations.CreateModel(
            name='Group',
            fields=[
                ('saved_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('save_id', models.UUIDField(default=uuid.uuid4)),
                ('created_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('updated_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('id', models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
                ('name', models.CharField(max_length=80)),
                ('alarm_permission', models.CharField(choices=[('everyone', 'Everyone'), ('leaders', 'Leaders')], default='everyone', max_length=16)),
                ('notify_alarm_changes', models.BooleanField(default=True)),
                ('notify_snoozed', models.BooleanField(default=True)),
                ('notify_dismissed', models.BooleanField(default=True)),
                ('notify_ignored', models.BooleanField(default=True)),
                ('alarm_time_basis', models.CharField(choices=[('member_local', 'Member Local'), ('group_time_zone', 'Group Time Zone')], default='member_local', max_length=24)),
                ('alarm_time_zone', models.CharField(default='UTC', max_length=100)),
                ('shared_answers', models.BooleanField(default=False)),
                ('deleted_at', models.DateTimeField(null=True)),
            ],
        ),
        migrations.CreateModel(
            name='Identity',
            fields=[
                ('saved_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('save_id', models.UUIDField(default=uuid.uuid4)),
                ('created_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('updated_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('id', models.CharField(max_length=64, primary_key=True, serialize=False)),
                ('name', models.CharField(max_length=64)),
                ('token_hash', models.BinaryField(max_length=32)),
                ('time_zone', models.CharField(default='UTC', max_length=100)),
                ('last_seen_at', models.DateTimeField(db_index=True, default=django.utils.timezone.now)),
                ('retired_at', models.DateTimeField(null=True)),
            ],
        ),
        migrations.CreateModel(
            name='SyncScope',
            fields=[
                ('id', models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
                ('head_revision', models.PositiveBigIntegerField(default=0)),
                ('retention_floor', models.PositiveBigIntegerField(default=0)),
            ],
        ),
        migrations.CreateModel(
            name='DeliveryWork',
            fields=[
                ('id', models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
                ('kind', models.CharField(choices=[('push', 'Push'), ('reschedule', 'Reschedule'), ('verify', 'Verify'), ('delete_sound', 'Delete Sound'), ('retire', 'Retire'), ('delete_group', 'Delete Group')], max_length=20)),
                ('deduplication_key', models.CharField(max_length=300, unique=True)),
                ('payload', models.JSONField(default=dict)),
                ('available_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('created_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('attempts', models.PositiveIntegerField(default=0)),
                ('lease_owner', models.UUIDField(null=True)),
                ('lease_until', models.DateTimeField(null=True)),
                ('failed_at', models.DateTimeField(null=True)),
                ('error_code', models.CharField(max_length=100, null=True)),
            ],
            options={
                'indexes': [models.Index(condition=models.Q(('failed_at', None)), fields=['kind', 'available_at'], name='work_ready_idx')],
            },
        ),
        migrations.CreateModel(
            name='PlayEntitlement',
            fields=[
                ('identity', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, primary_key=True, serialize=False, to='social.identity')),
                ('verified_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('expires_at', models.DateTimeField()),
            ],
        ),
        migrations.CreateModel(
            name='GroupInvitation',
            fields=[
                ('id', models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
                ('token_hash', models.BinaryField(unique=True)),
                ('expires_at', models.DateTimeField()),
                ('consumed_at', models.DateTimeField(null=True)),
                ('created_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('group', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to='social.group')),
                ('consumed_by', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='invitations_consumed', to='social.identity')),
                ('created_by', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='invitations_created', to='social.identity')),
            ],
        ),
        migrations.AddField(
            model_name='group',
            name='created_by',
            field=models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, to='social.identity'),
        ),
        migrations.CreateModel(
            name='PushSubscription',
            fields=[
                ('token', models.CharField(max_length=4096, primary_key=True, serialize=False)),
                ('updated_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('identity', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='push_subscriptions', to='social.identity')),
            ],
        ),
        migrations.CreateModel(
            name='SharedSound',
            fields=[
                ('id', models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
                ('title', models.CharField(max_length=200)),
                ('sha256', models.CharField(max_length=64, null=True)),
                ('byte_length', models.PositiveIntegerField(null=True)),
                ('duration_ms', models.PositiveIntegerField(null=True)),
                ('object_key', models.CharField(max_length=256, unique=True)),
                ('staging_key', models.CharField(max_length=256, unique=True)),
                ('status', models.CharField(choices=[('pending', 'Pending'), ('verifying', 'Verifying'), ('ready', 'Ready'), ('failed', 'Failed'), ('deleting', 'Deleting')], default='pending', max_length=16)),
                ('failure_code', models.CharField(max_length=64, null=True)),
                ('created_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('ready_at', models.DateTimeField(null=True)),
                ('group', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to='social.group')),
                ('uploaded_by', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, to='social.identity')),
            ],
        ),
        migrations.CreateModel(
            name='SharedAlarm',
            fields=[
                ('saved_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('save_id', models.UUIDField(default=uuid.uuid4)),
                ('created_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('updated_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('id', models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
                ('revision', models.PositiveIntegerField(default=1)),
                ('local_time_ms', models.PositiveIntegerField()),
                ('label', models.CharField(max_length=200, null=True)),
                ('enabled', models.BooleanField()),
                ('days', django.contrib.postgres.fields.ArrayField(base_field=models.PositiveSmallIntegerField(), size=7)),
                ('vibrate', models.BooleanField()),
                ('start_date', models.DateField()),
                ('repeat_interval', models.PositiveSmallIntegerField()),
                ('repeat_unit', models.CharField(choices=[('DAY', 'Day'), ('WEEK', 'Week'), ('MONTH', 'Month'), ('YEAR', 'Year')], max_length=8)),
                ('repeat_anchor', models.CharField(choices=[('DAY_OF_MONTH', 'Day Of Month'), ('DAY_OF_WEEK', 'Day Of Week')], max_length=16)),
                ('repeat_duration', models.PositiveSmallIntegerField(null=True)),
                ('repeat_duration_unit', models.CharField(choices=[('DAY', 'Day'), ('WEEK', 'Week'), ('MONTH', 'Month'), ('YEAR', 'Year')], max_length=8)),
                ('end_date', models.DateField(null=True)),
                ('end_occurrences', models.PositiveSmallIntegerField(null=True)),
                ('advanced', models.BooleanField(default=False)),
                ('snooze_enabled', models.BooleanField()),
                ('snooze_minutes', models.PositiveSmallIntegerField()),
                ('vibration_pattern', django.contrib.postgres.fields.ArrayField(base_field=models.PositiveIntegerField(), size=256)),
                ('vibration_pattern_name', models.CharField(max_length=80)),
                ('sound_mode', models.CharField(choices=[('off', 'Off'), ('member_default', 'Member Default'), ('shared', 'Shared')], default='member_default', max_length=16)),
                ('deleted_at', models.DateTimeField(null=True)),
                ('inactive_cycle_streak', models.PositiveSmallIntegerField(default=0)),
                ('last_evaluated_cycle_date', models.DateField(null=True)),
                ('created_by', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='alarms_created', to='social.identity')),
                ('group', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='alarms', to='social.group')),
                ('updated_by', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='alarms_updated', to='social.identity')),
                ('sound', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='alarms', to='social.sharedsound')),
            ],
        ),
        migrations.CreateModel(
            name='SharedTimer',
            fields=[
                ('saved_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('save_id', models.UUIDField(default=uuid.uuid4)),
                ('created_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('updated_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('id', models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
                ('label', models.CharField(max_length=120, null=True)),
                ('duration_seconds', models.PositiveIntegerField()),
                ('increment_seconds', models.PositiveIntegerField()),
                ('expires_at', models.DateTimeField()),
                ('vibrate', models.BooleanField(default=True)),
                ('vibration_pattern', django.contrib.postgres.fields.ArrayField(base_field=models.PositiveIntegerField(), size=256)),
                ('vibration_pattern_name', models.CharField(max_length=80)),
                ('sound_mode', models.CharField(choices=[('off', 'Off'), ('member_default', 'Member Default'), ('shared', 'Shared')], default='member_default', max_length=16)),
                ('deleted_at', models.DateTimeField(null=True)),
                ('group', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='timers', to='social.group')),
                ('sound', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='timers', to='social.sharedsound')),
                ('started_by', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, to='social.identity')),
            ],
        ),
        migrations.AddField(
            model_name='identity',
            name='scope',
            field=models.OneToOneField(on_delete=django.db.models.deletion.PROTECT, related_name='identity', to='social.syncscope'),
        ),
        migrations.AddField(
            model_name='group',
            name='scope',
            field=models.OneToOneField(on_delete=django.db.models.deletion.PROTECT, related_name='group', to='social.syncscope'),
        ),
        migrations.CreateModel(
            name='SyncVersion',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('revision', models.PositiveBigIntegerField()),
                ('ordinal', models.PositiveIntegerField()),
                ('kind', models.CharField(max_length=32)),
                ('key', models.CharField(max_length=200)),
                ('action', models.CharField(max_length=32)),
                ('representation', models.JSONField()),
                ('created_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('superseded_at', models.DateTimeField(null=True)),
                ('recipient', models.ForeignKey(null=True, on_delete=django.db.models.deletion.CASCADE, to='social.identity')),
                ('scope', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='versions', to='social.syncscope')),
            ],
        ),
        migrations.CreateModel(
            name='WorkerHeartbeat',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('instance_id', models.UUIDField()),
                ('work_class', models.CharField(max_length=20)),
                ('progressed_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('error_code', models.CharField(max_length=100, null=True)),
            ],
            options={
                'constraints': [models.UniqueConstraint(fields=('instance_id', 'work_class'), name='worker_class_unique')],
            },
        ),
        migrations.CreateModel(
            name='GroupMembership',
            fields=[
                ('saved_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('save_id', models.UUIDField(default=uuid.uuid4)),
                ('created_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('updated_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('id', models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
                ('role', models.CharField(choices=[('member', 'Member'), ('leader', 'Leader')], default='member', max_length=16)),
                ('notify_membership', models.BooleanField(default=True)),
                ('notify_administrative', models.BooleanField(default=True)),
                ('preferences_saved_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('preferences_save_id', models.UUIDField(default=uuid.uuid4)),
                ('joined_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('removed_at', models.DateTimeField(null=True)),
                ('group', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='memberships', to='social.group')),
                ('identity', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='memberships', to='social.identity')),
            ],
            options={
                'constraints': [models.UniqueConstraint(condition=models.Q(('removed_at', None)), fields=('group', 'identity'), name='membership_active_unique'), models.CheckConstraint(condition=models.Q(('role__in', ['member', 'leader'])), name='membership_role_valid')],
            },
        ),
        migrations.CreateModel(
            name='GroupActivity',
            fields=[
                ('id', models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
                ('revision', models.PositiveBigIntegerField()),
                ('ordinal', models.PositiveIntegerField()),
                ('entity_type', models.CharField(max_length=32)),
                ('entity_id', models.CharField(max_length=200)),
                ('action', models.CharField(max_length=32)),
                ('group_label', models.CharField(max_length=80)),
                ('actor_label', models.CharField(max_length=64, null=True)),
                ('subject_label', models.CharField(max_length=64, null=True)),
                ('entity_label', models.CharField(max_length=200, null=True)),
                ('entity_time', models.PositiveIntegerField(null=True)),
                ('details', models.JSONField(null=True)),
                ('occurred_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('group', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to='social.group')),
                ('actor', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='group_activity', to='social.identity')),
                ('recipient', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='received_activity', to='social.identity')),
                ('subject', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, related_name='subject_activity', to='social.identity')),
            ],
            options={
                'indexes': [models.Index(fields=['group', '-revision', '-ordinal'], name='group_activity_cursor_idx')],
            },
        ),
        migrations.CreateModel(
            name='OperationReceipt',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('operation_id', models.UUIDField()),
                ('fingerprint', models.CharField(max_length=64)),
                ('status', models.PositiveSmallIntegerField(null=True)),
                ('response', models.JSONField(null=True)),
                ('completed_at', models.DateTimeField(null=True)),
                ('identity', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to='social.identity')),
            ],
            options={
                'constraints': [models.UniqueConstraint(fields=('identity', 'operation_id'), name='operation_identity_unique')],
            },
        ),
        migrations.CreateModel(
            name='AlarmOccurrence',
            fields=[
                ('id', models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
                ('alarm_revision', models.PositiveIntegerField()),
                ('occurrence_key', models.CharField(max_length=200)),
                ('cycle_date', models.DateField()),
                ('trigger_at', models.DateTimeField()),
                ('deadline_at', models.DateTimeField()),
                ('state', models.CharField(choices=[('pending', 'Pending'), ('snoozed', 'Snoozed'), ('dismissed', 'Dismissed'), ('ignored', 'Ignored'), ('canceled', 'Canceled')], default='pending', max_length=16)),
                ('resolved_at', models.DateTimeField(null=True)),
                ('group', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to='social.group')),
                ('identity', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to='social.identity')),
                ('alarm', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='occurrences', to='social.sharedalarm')),
            ],
            options={
                'indexes': [models.Index(condition=models.Q(('state', 'pending')), fields=['deadline_at', 'group'], name='occurrence_due_idx'), models.Index(fields=['alarm', 'alarm_revision', 'cycle_date'], name='occurrence_cycle_idx')],
                'constraints': [models.UniqueConstraint(fields=('alarm', 'identity', 'occurrence_key'), name='occurrence_identity_key_unique'), models.CheckConstraint(condition=models.Q(('alarm_revision__gte', 1)), name='occurrence_revision_positive'), models.CheckConstraint(condition=models.Q(('deadline_at__gt', models.F('trigger_at'))), name='occurrence_deadline_after_trigger'), models.CheckConstraint(condition=models.Q(('state__in', ['pending', 'snoozed', 'dismissed', 'ignored', 'canceled'])), name='occurrence_state_valid')],
            },
        ),
        migrations.CreateModel(
            name='AlarmDelivery',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('revision', models.PositiveIntegerField()),
                ('received_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('identity', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to='social.identity')),
                ('alarm', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to='social.sharedalarm')),
            ],
            options={
                'constraints': [models.UniqueConstraint(fields=('alarm', 'identity', 'revision'), name='delivery_revision_unique')],
            },
        ),
        migrations.CreateModel(
            name='AlarmActivity',
            fields=[
                ('id', models.UUIDField(default=uuid.uuid4, primary_key=True, serialize=False)),
                ('alarm_revision', models.PositiveIntegerField()),
                ('occurrence_key', models.CharField(max_length=200, null=True)),
                ('kind', models.CharField(choices=[('snoozed', 'Snoozed'), ('dismissed', 'Dismissed'), ('ignored', 'Ignored')], max_length=16)),
                ('occurred_at', models.DateTimeField()),
                ('reason', models.CharField(max_length=40, null=True)),
                ('request_fingerprint', models.CharField(max_length=64)),
                ('created_at', models.DateTimeField(default=django.utils.timezone.now)),
                ('group', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to='social.group')),
                ('identity', models.ForeignKey(null=True, on_delete=django.db.models.deletion.SET_NULL, to='social.identity')),
                ('alarm', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='activities', to='social.sharedalarm')),
            ],
            options={
                'indexes': [models.Index(fields=['alarm', 'identity', 'occurrence_key'], name='activity_outcome_idx')],
                'constraints': [models.CheckConstraint(condition=models.Q(('kind__in', ['snoozed', 'dismissed', 'ignored'])), name='activity_kind_valid')],
            },
        ),
        migrations.AddConstraint(
            model_name='sharedsound',
            constraint=models.CheckConstraint(condition=models.Q(('byte_length__range', (1, 33554432))), name='sound_bytes_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedsound',
            constraint=models.CheckConstraint(condition=models.Q(('duration_ms__range', (1, 300000))), name='sound_duration_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedsound',
            constraint=models.CheckConstraint(condition=models.Q(('sha256__regex', '^[a-f0-9]{64}$')), name='sound_hash_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedsound',
            constraint=models.CheckConstraint(condition=models.Q(('status__in', ['pending', 'verifying', 'ready', 'failed', 'deleting'])), name='sound_status_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('revision__gte', 1)), name='alarm_revision_positive'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('local_time_ms__lt', 86400000)), name='alarm_time_within_day'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('repeat_interval__range', (1, 999))), name='alarm_repeat_interval_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('repeat_duration', None), ('repeat_duration__range', (1, 999)), _connector='OR'), name='alarm_repeat_duration_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('end_occurrences', None), ('end_occurrences__range', (1, 999)), _connector='OR'), name='alarm_end_count_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('snooze_minutes__range', (1, 1440))), name='alarm_snooze_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('days__contained_by', [0, 1, 2, 3, 4, 5, 6])), name='alarm_days_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('repeat_unit__in', ['DAY', 'WEEK', 'MONTH', 'YEAR'])), name='alarm_repeat_unit_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('repeat_duration_unit__in', ['DAY', 'WEEK', 'MONTH', 'YEAR'])), name='alarm_duration_unit_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('repeat_anchor__in', ['DAY_OF_MONTH', 'DAY_OF_WEEK'])), name='alarm_anchor_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('sound_mode__in', ['off', 'member_default', 'shared'])), name='alarm_sound_mode_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('end_date', None), ('end_date__gte', models.F('start_date')), _connector='OR'), name='alarm_date_range_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedalarm',
            constraint=models.CheckConstraint(condition=models.Q(('inactive_cycle_streak__lte', 3)), name='alarm_inactivity_valid'),
        ),
        migrations.AddIndex(
            model_name='sharedtimer',
            index=models.Index(condition=models.Q(('deleted_at', None)), fields=['expires_at'], name='timer_expiry_idx'),
        ),
        migrations.AddConstraint(
            model_name='sharedtimer',
            constraint=models.CheckConstraint(condition=models.Q(('duration_seconds__range', (1, 86400))), name='timer_duration_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedtimer',
            constraint=models.CheckConstraint(condition=models.Q(('increment_seconds__range', (1, 3600))), name='timer_increment_valid'),
        ),
        migrations.AddConstraint(
            model_name='sharedtimer',
            constraint=models.CheckConstraint(condition=models.Q(('sound_mode__in', ['off', 'member_default', 'shared'])), name='timer_sound_mode_valid'),
        ),
        migrations.AddConstraint(
            model_name='identity',
            constraint=models.CheckConstraint(condition=models.Q(('id__regex', '^[a-f0-9]{64}$')), name='identity_id_hex'),
        ),
        migrations.AddConstraint(
            model_name='identity',
            constraint=models.CheckConstraint(condition=models.Q(('name', ''), _negated=True), name='identity_name_nonempty'),
        ),
        migrations.AddConstraint(
            model_name='group',
            constraint=models.CheckConstraint(condition=models.Q(('name', ''), _negated=True), name='group_name_nonempty'),
        ),
        migrations.AddConstraint(
            model_name='group',
            constraint=models.CheckConstraint(condition=models.Q(('alarm_permission__in', ['everyone', 'leaders'])), name='group_permission_valid'),
        ),
        migrations.AddConstraint(
            model_name='group',
            constraint=models.CheckConstraint(condition=models.Q(('alarm_time_basis__in', ['member_local', 'group_time_zone'])), name='group_time_basis_valid'),
        ),
        migrations.AddIndex(
            model_name='syncversion',
            index=models.Index(fields=['scope', 'kind', 'key', '-revision'], name='sync_resource_version_idx'),
        ),
        migrations.AddConstraint(
            model_name='syncversion',
            constraint=models.UniqueConstraint(fields=('scope', 'revision', 'ordinal'), name='sync_revision_ordinal_unique'),
        ),
    ]

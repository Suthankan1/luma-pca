# Alarms to an SNS topic (email to the on-call address). Thresholds are starting points to
# tune after the pilot. Log-based alarms count our own fixed log lines, never personal data.
resource "aws_sns_topic" "alerts" {
  name              = "${var.name}-alerts"
  kms_master_key_id = "alias/aws/sns"
  tags              = var.tags
}

resource "aws_sns_topic_subscription" "email" {
  for_each  = toset(var.alert_emails)
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = each.value
}

locals {
  actions = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${var.name}-5xx"
  alarm_description   = "Server errors from the web app or API (runbook: docs/delivery/runbooks/INCIDENT-RESPONSE.md)"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  dimensions          = { LoadBalancer = var.alb_arn_suffix }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 20
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  ok_actions          = local.actions
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "api_unhealthy" {
  alarm_name          = "${var.name}-api-unhealthy"
  alarm_description   = "An API task fails its readiness check"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  dimensions          = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.api_target_group_arn_suffix }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = local.actions
  ok_actions          = local.actions
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "db_cpu" {
  alarm_name          = "${var.name}-db-cpu"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = var.db_instance_id }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = local.actions
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "db_storage" {
  alarm_name          = "${var.name}-db-free-storage"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = var.db_instance_id }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2147483648
  comparison_operator = "LessThanThreshold"
  alarm_actions       = local.actions
  tags                = var.tags
}

# The API logs "dead-lettered" when a Zoom receipt gives up (ZoomReceiptProcessor) and
# "could not be fetched" when a participant report fails (ZoomReportService).
resource "aws_cloudwatch_log_metric_filter" "zoom_dead" {
  name           = "${var.name}-zoom-dead-lettered"
  log_group_name = var.api_log_group
  pattern        = "\"dead-lettered\""
  metric_transformation {
    name      = "ZoomReceiptsDeadLettered"
    namespace = "LUMA/${var.name}"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "zoom_dead" {
  alarm_name          = "${var.name}-zoom-dead-lettered"
  alarm_description   = "A Zoom event could not be processed; replay it from Zoom review once fixed"
  namespace           = "LUMA/${var.name}"
  metric_name         = aws_cloudwatch_log_metric_filter.zoom_dead.metric_transformation[0].name
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  tags                = var.tags
}

resource "aws_cloudwatch_log_metric_filter" "zoom_report_failed" {
  name           = "${var.name}-zoom-report-failed"
  log_group_name = var.api_log_group
  pattern        = "\"report could not be fetched\""
  metric_transformation {
    name      = "ZoomReportFetchFailed"
    namespace = "LUMA/${var.name}"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "zoom_report_failed" {
  alarm_name          = "${var.name}-zoom-report-failed"
  alarm_description   = "Zoom participant reports are failing; attendance cannot be finalised"
  namespace           = "LUMA/${var.name}"
  metric_name         = aws_cloudwatch_log_metric_filter.zoom_report_failed.metric_transformation[0].name
  statistic           = "Sum"
  period              = 3600
  evaluation_periods  = 1
  threshold           = 3
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  tags                = var.tags
}

# More fixed log phrases worth a page. AlarmLogPhrasesTest (backend) fails if the API stops
# writing any phrase listed in this file, so an alarm can never go quiet after a rewording.
locals {
  log_alarms = {
    webhook-signature = {
      pattern     = "\"Rejected Zoom webhook: signature\""
      period      = 3600
      threshold   = 10
      description = "Many Zoom webhooks fail signature checks: check the secret token and the server clock (WEBHOOK-INCIDENT.md)"
    }
    webhook-store = {
      pattern     = "\"Could not store Zoom webhook\""
      period      = 300
      threshold   = 0
      description = "Zoom webhooks cannot be stored (database trouble); Zoom retries for a while, act quickly"
    }
    retention = {
      pattern     = "\"Retention run failed\""
      period      = 86400
      threshold   = 0
      description = "The nightly privacy retention job failed"
    }
    email = {
      pattern     = "\"email for account\""
      period      = 3600
      threshold   = 5
      description = "Account emails (invitations, resets, notices) are failing: check the mail provider"
    }
  }
}

resource "aws_cloudwatch_log_metric_filter" "phrase" {
  for_each       = local.log_alarms
  name           = "${var.name}-${each.key}"
  log_group_name = var.api_log_group
  pattern        = each.value.pattern
  metric_transformation {
    name      = "LogPhrase-${each.key}"
    namespace = "LUMA/${var.name}"
    value     = "1"
  }
}

resource "aws_cloudwatch_metric_alarm" "phrase" {
  for_each            = local.log_alarms
  alarm_name          = "${var.name}-${each.key}"
  alarm_description   = each.value.description
  namespace           = "LUMA/${var.name}"
  metric_name         = aws_cloudwatch_log_metric_filter.phrase[each.key].metric_transformation[0].name
  statistic           = "Sum"
  period              = each.value.period
  evaluation_periods  = 1
  threshold           = each.value.threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = local.actions
  tags                = var.tags
}

# The queue for Zoom receipt processing when it moves out of the API process (ADR-0006), with a
# dead-letter queue after five failed receives. Today the database queue does this job.
resource "aws_sqs_queue" "dlq" {
  name                      = "${var.name}-zoom-receipts-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
  tags                      = var.tags
}

resource "aws_sqs_queue" "main" {
  name                       = "${var.name}-zoom-receipts"
  visibility_timeout_seconds = 120
  message_retention_seconds  = 345600
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = 5
  })
  tags = var.tags
}

variable "name" {
  type = string
}

variable "alert_emails" {
  type        = list(string)
  description = "On-call addresses (the academy's, set at deployment; never committed)."
}

variable "alb_arn_suffix" {
  type = string
}

variable "api_target_group_arn_suffix" {
  type = string
}

variable "db_instance_id" {
  type = string
}

variable "dlq_name" {
  type = string
}

variable "api_log_group" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}

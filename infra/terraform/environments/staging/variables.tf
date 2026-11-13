variable "environment" {
  type    = string
  default = "staging"
}

variable "region" {
  type        = string
  default     = "ap-south-1"
  description = "Mumbai is the nearest region to Sri Lanka; confirm with the academy (data residency)."
}

variable "domain" {
  type        = string
  description = "The academy's domain for LUMA, e.g. luma.example.lk."
}

variable "certificate_arn" {
  type = string
}

variable "api_image" {
  type = string
}

variable "web_image" {
  type = string
}

variable "academy_id" {
  type        = string
  description = "The academy's LUMA ID, created by scripts/bootstrap-owner.sh."
}

variable "webinar_allow_list" {
  type        = string
  default     = ""
  description = "Comma-separated webinar IDs to store; empty stores all of the account's webinars."
}

variable "alert_emails" {
  type = list(string)
}

variable "nat_gateways" {
  type    = number
  default = 1
}

variable "api_min_tasks" {
  type    = number
  default = 2
}

variable "web_min_tasks" {
  type    = number
  default = 2
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.small"
}

variable "db_multi_az" {
  type    = bool
  default = false
}

variable "db_backup_retention_days" {
  type    = number
  default = 7
}

variable "export_retention_days" {
  type    = number
  default = 30
}

variable "mail_host" {
  type        = string
  description = "SMTP host of the academy's mail provider (e.g. Amazon SES in the region)."
}

variable "mail_from" {
  type = string
}

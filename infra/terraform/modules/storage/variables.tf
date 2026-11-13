variable "name" {
  type = string
}

variable "bucket_suffix" {
  type        = string
  description = "Makes the bucket name globally unique, e.g. the AWS account ID."
}

variable "export_retention_days" {
  type        = number
  default     = 30
  description = "Set by the academy's retention policy (OPEN-QUESTIONS P0)."
}

variable "backup_retention_days" {
  type    = number
  default = 35
}

variable "tags" {
  type    = map(string)
  default = {}
}

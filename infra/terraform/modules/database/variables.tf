variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "app_security_group_id" {
  type = string
}

variable "instance_class" {
  type        = string
  default     = "db.t4g.small"
  description = "Size for 600 concurrent users is to be confirmed with the load test (PERF-1)."
}

variable "allocated_storage_gb" {
  type    = number
  default = 20
}

variable "max_allocated_storage_gb" {
  type    = number
  default = 100
}

variable "multi_az" {
  type        = bool
  default     = false
  description = "A standby in a second zone; set by the academy's RTO (OPEN-QUESTIONS P1)."
}

variable "backup_retention_days" {
  type        = number
  default     = 7
  description = "Set by the academy's RPO and retention policy (OPEN-QUESTIONS P1)."
  validation {
    condition     = var.backup_retention_days >= 1 && var.backup_retention_days <= 35
    error_message = "RDS keeps automated backups for 1 to 35 days."
  }
}

variable "tags" {
  type    = map(string)
  default = {}
}

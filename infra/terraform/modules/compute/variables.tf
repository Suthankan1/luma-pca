variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "certificate_arn" {
  type        = string
  description = "ACM certificate for the academy's domain."
}

variable "api_image" {
  type = string
}

variable "web_image" {
  type = string
}

variable "api_environment" {
  type        = map(string)
  default     = {}
  description = "Plain settings only; never secrets."
}

variable "web_environment" {
  type    = map(string)
  default = {}
}

variable "api_secret_arns" {
  type        = map(string)
  description = "Environment variable name to Secrets Manager ARN (database password, Zoom credentials, signing key, mail)."
}

variable "web_secret_arns" {
  type        = map(string)
  description = "Environment variable name to Secrets Manager ARN (session key)."
}

variable "private_bucket_arn" {
  type = string
}

variable "api_cpu" {
  type    = number
  default = 1024
}

variable "api_memory" {
  type    = number
  default = 2048
}

variable "web_cpu" {
  type    = number
  default = 512
}

variable "web_memory" {
  type    = number
  default = 1024
}

variable "api_min_tasks" {
  type    = number
  default = 2
}

variable "api_max_tasks" {
  type    = number
  default = 4
}

variable "web_min_tasks" {
  type    = number
  default = 2
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "waf_rate_limit" {
  type        = number
  default     = 2000
  description = "Requests per five minutes from one address before the firewall blocks it. A class behind one school or home network shares an address, so keep this generous."
}

variable "tags" {
  type    = map(string)
  default = {}
}

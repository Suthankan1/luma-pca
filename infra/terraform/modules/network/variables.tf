variable "name" {
  type        = string
  description = "Prefix for resource names, e.g. luma-staging."
}

variable "cidr_block" {
  type    = string
  default = "10.40.0.0/16"
}

variable "nat_gateways" {
  type        = number
  default     = 1
  description = "1 (cheaper, one zone for egress) or 2 (each zone has its own)."
  validation {
    condition     = var.nat_gateways >= 1 && var.nat_gateways <= 2
    error_message = "nat_gateways must be 1 or 2."
  }
}

variable "interface_endpoints" {
  type        = bool
  default     = false
  description = "Private endpoints for ECR, CloudWatch Logs and Secrets Manager (hourly cost per zone)."
}

variable "tags" {
  type    = map(string)
  default = {}
}

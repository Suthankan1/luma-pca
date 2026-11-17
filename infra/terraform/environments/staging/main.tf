# Staging: the whole proposed AWS design (ADR-0006) in one small environment. NOT APPLIED.
# Running `terraform apply` creates chargeable resources: only with the academy's budget
# approval, its AWS account and a reviewed plan (infra/AGENTS.md).
terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }
  # Remote state, once the account exists:
  # backend "s3" { bucket = "<state bucket>" key = "luma/staging.tfstate" region = "ap-south-1" use_lockfile = true encrypt = true }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      project     = "luma"
      environment = var.environment
      managed_by  = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}

locals {
  name = "luma-${var.environment}"
}

module "network" {
  source              = "../../modules/network"
  name                = local.name
  nat_gateways        = var.nat_gateways
  interface_endpoints = var.interface_endpoints
}

module "storage" {
  source                = "../../modules/storage"
  name                  = local.name
  bucket_suffix         = data.aws_caller_identity.current.account_id
  export_retention_days = var.export_retention_days
}

# Secret containers only; values are put in by an operator (aws secretsmanager put-secret-value),
# never through Terraform, so they never reach state files or Git.
resource "aws_secretsmanager_secret" "app" {
  for_each = toset(["auth-signing-key", "auth-mfa-encryption-key", "session-key", "zoom-api", "zoom-webhook", "mail"])
  name     = "${local.name}/${each.value}"
}

module "compute" {
  source             = "../../modules/compute"
  name               = local.name
  vpc_id             = module.network.vpc_id
  public_subnet_ids  = module.network.public_subnet_ids
  private_subnet_ids = module.network.private_subnet_ids
  certificate_arn    = var.certificate_arn
  api_image          = var.api_image
  web_image          = var.web_image
  private_bucket_arn = module.storage.bucket_arn
  api_min_tasks      = var.api_min_tasks
  web_min_tasks      = var.web_min_tasks
  api_environment = {
    DB_URL                               = "jdbc:postgresql://${module.database.endpoint}:5432/luma?sslmode=require"
    DB_USERNAME                          = "luma_admin"
    LUMA_AUTH_ISSUER                     = "https://${var.domain}"
    LUMA_MAIL_WEB_ORIGIN                 = "https://${var.domain}"
    LUMA_MAIL_ENABLED                    = "true"
    LUMA_RATE_LIMIT_ENABLED              = "true"
    LUMA_TRUSTED_PROXIES                 = module.network.vpc_cidr
    LUMA_ZOOM_WEBHOOK_ACADEMY_ID         = var.academy_id
    LUMA_ZOOM_WEBHOOK_WEBINAR_ALLOW_LIST = var.webinar_allow_list
    SPRING_MAIL_HOST                     = var.mail_host
    SPRING_MAIL_PORT                     = "587"
    LUMA_MAIL_FROM                       = var.mail_from
  }
  api_secret_arns = {
    DB_PASSWORD                    = "${module.database.master_secret_arn}:password::"
    LUMA_AUTH_SIGNING_KEY_PEM      = aws_secretsmanager_secret.app["auth-signing-key"].arn
    LUMA_AUTH_MFA_ENCRYPTION_KEY   = aws_secretsmanager_secret.app["auth-mfa-encryption-key"].arn
    LUMA_ZOOM_API_ACCOUNT_ID       = "${aws_secretsmanager_secret.app["zoom-api"].arn}:account_id::"
    LUMA_ZOOM_API_CLIENT_ID        = "${aws_secretsmanager_secret.app["zoom-api"].arn}:client_id::"
    LUMA_ZOOM_API_CLIENT_SECRET    = "${aws_secretsmanager_secret.app["zoom-api"].arn}:client_secret::"
    LUMA_ZOOM_WEBHOOK_SECRET_TOKEN = aws_secretsmanager_secret.app["zoom-webhook"].arn
    SPRING_MAIL_USERNAME           = "${aws_secretsmanager_secret.app["mail"].arn}:username::"
    SPRING_MAIL_PASSWORD           = "${aws_secretsmanager_secret.app["mail"].arn}:password::"
  }
  web_environment = {
    LUMA_WEB_ORIGIN         = "https://${var.domain}"
    LUMA_TRUSTED_PROXY_HOPS = "1"
  }
  web_secret_arns = {
    LUMA_SESSION_KEY = aws_secretsmanager_secret.app["session-key"].arn
  }
}

module "database" {
  source                = "../../modules/database"
  name                  = local.name
  vpc_id                = module.network.vpc_id
  private_subnet_ids    = module.network.private_subnet_ids
  app_security_group_id = module.compute.app_security_group_id
  instance_class        = var.db_instance_class
  multi_az              = var.db_multi_az
  backup_retention_days = var.db_backup_retention_days
  snapshot_keep_days    = var.db_snapshot_keep_days
}

module "monitoring" {
  source                      = "../../modules/monitoring"
  name                        = local.name
  alert_emails                = var.alert_emails
  alb_arn_suffix              = module.compute.alb_arn_suffix
  api_target_group_arn_suffix = module.compute.api_target_group_arn_suffix
  db_instance_id              = module.database.instance_id
  api_log_group               = module.compute.api_log_group
}

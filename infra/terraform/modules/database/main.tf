# Private, encrypted PostgreSQL 17 (the version the tests and restore drill use). Only the
# application's security group may connect; TLS is required; automated backups and deletion
# protection are on. The master password is generated and kept by RDS in Secrets Manager.
resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-db"
  subnet_ids = var.private_subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "db" {
  name        = "${var.name}-db"
  description = "PostgreSQL from the application only"
  vpc_id      = var.vpc_id
  tags        = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "from_app" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = var.app_security_group_id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "API tasks"
}

resource "aws_db_parameter_group" "this" {
  name   = "${var.name}-pg17"
  family = "postgres17"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }
  tags = var.tags
}

resource "aws_db_instance" "this" {
  identifier                      = "${var.name}-db"
  engine                          = "postgres"
  engine_version                  = "17"
  instance_class                  = var.instance_class
  allocated_storage               = var.allocated_storage_gb
  max_allocated_storage           = var.max_allocated_storage_gb
  storage_type                    = "gp3"
  storage_encrypted               = true
  db_name                         = "luma"
  username                        = "luma_admin"
  manage_master_user_password     = true
  db_subnet_group_name            = aws_db_subnet_group.this.name
  vpc_security_group_ids          = [aws_security_group.db.id]
  parameter_group_name            = aws_db_parameter_group.this.name
  publicly_accessible             = false
  multi_az                        = var.multi_az
  backup_retention_period         = var.backup_retention_days
  backup_window                   = "19:00-19:30"
  maintenance_window              = "sun:20:00-sun:21:00"
  copy_tags_to_snapshot           = true
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${var.name}-db-final"
  auto_minor_version_upgrade      = true
  enabled_cloudwatch_logs_exports = ["postgresql"]
  tags                            = var.tags
}

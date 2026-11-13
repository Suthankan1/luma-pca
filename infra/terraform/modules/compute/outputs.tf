output "app_security_group_id" {
  value = aws_security_group.app.id
}

output "alb_dns_name" {
  value = aws_lb.this.dns_name
}

output "alb_arn_suffix" {
  value = aws_lb.this.arn_suffix
}

output "api_target_group_arn_suffix" {
  value = aws_lb_target_group.api.arn_suffix
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "api_log_group" {
  value = aws_cloudwatch_log_group.api.name
}

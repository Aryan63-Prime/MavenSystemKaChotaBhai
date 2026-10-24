package auth

import "errors"

var (
	ErrInvalidToken    = errors.New("invalid token")
	ErrMissingToken    = errors.New("missing token")
	ErrRevokedToken    = errors.New("token has been revoked")
	ErrSuspendedToken  = errors.New("administrator token is currently suspended")
	ErrPrivilegeDenied = errors.New("only SOVEREIGN_ROOT_ADMIN (ADMIN-001 on Vivo iQOO 13) can perform IAM mutations")
)

func IsAuthenticationError(err error) bool {
	return errors.Is(err, ErrMissingToken) || errors.Is(err, ErrInvalidToken) || errors.Is(err, ErrRevokedToken) || errors.Is(err, ErrSuspendedToken)
}

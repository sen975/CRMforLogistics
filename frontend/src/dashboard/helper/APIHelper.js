// Configure axios to talk to your own backend.
// Change API_BASE_URL to match your Python / Node / Go server.
export default axios => {
  const API_BASE_URL = 'http://localhost:8000/api/v1';
  const wootApi = axios.create({ baseURL: API_BASE_URL });

  // If you implement token-based auth later, attach headers here:
  // const token = localStorage.getItem('auth_token');
  // if (token) {
  //   wootApi.defaults.headers.common['Authorization'] = `Bearer ${token}`;
  // }

  // Response interceptor — add auth error handling here
  wootApi.interceptors.response.use(
    response => response,
    error => {
      if (error.response && error.response.status === 401) {
        // Redirect to login or refresh token
      }
      return Promise.reject(error);
    }
  );

  return wootApi;
};

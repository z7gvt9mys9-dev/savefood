export const volunteerMapUrl = (apiUrl, city) =>
  `${apiUrl}/volunteers/map?${new URLSearchParams({ city }).toString()}`;
